package dev.securenotes.backup

import android.content.Context
import android.net.Uri
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.security.Passphrase
import dev.securenotes.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable data class BackupManifest(
    val version: Int = 3, val notes: List<Note>, val attachments: List<AttachmentRow>,
    val sort: SortOrder? = null, val order: List<String>? = null,
    val tags: List<Tag> = emptyList(),
) {
    fun orderedIds(): List<String> = if (version == 1) sortedNotes(notes, checkNotNull(sort)).map(Note::id) else checkNotNull(order)
}

class BackupService(private val context: Context, private val repository: NotesRepository, private val checkAccess: () -> Unit) {
    suspend fun checkHeader(uri: Uri) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        checkAccess()
        context.contentResolver.openInputStream(uri)!!.use(BackupCodec::validateHeader)
    }
    private val cache = File(context.cacheDir, "backups").apply { mkdirs() }
    @Volatile var pending: Vault? = null
        private set
    /**
     * Encrypts a backup under the vault's recovery passphrase. Returns the file and a content fingerprint,
     * or null when the content still matches [unchangedSince].
     */
    suspend fun create(progress: (String) -> Unit, unchangedSince: String? = null): Pair<File, String>? = repository.access { v ->
        val operation = currentCoroutineContext()
        val notes = v.orderedNotes()
        val ids = notes.flatMap { it.document.attachments }.toSet()
        val attachments = v.dao.attachments().filter { it.id in ids }
        val manifest = BackupManifest(notes = notes, attachments = attachments.map { it.copy(indexed = false, nextPage = 0) }, order = notes.map(Note::id), tags = v.dao.tags().map { it.decode() })
        val manifestBytes = documentJson.encodeToString(manifest).toByteArray()
        val fingerprint = Crypto.hash(manifestBytes).hex()
        if (fingerprint == unchangedSince) return@access null
        val password = checkNotNull(v.passphrase()) { "Recovery passphrase missing" }.toCharArray()
        val output = File(cache, "${newId()}.ssnb")
        try {
            output.outputStream().buffered().use { encrypted -> BackupCodec.encrypt(encrypted, password) { clear ->
                val zip = ZipOutputStream(clear)
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
                attachments.forEachIndexed { index, a ->
                    checkAccess(); operation.ensureActive(); progress("Encrypting attachment ${index + 1} of ${attachments.size}")
                    zip.putNextEntry(ZipEntry("attachments/${a.id}"))
                    v.read(a.id).use { copyChecked(it, zip) { operation.ensureActive() } }; zip.closeEntry()
                }
                zip.finish(); zip.flush()
            } }
            output to fingerprint
        } catch (e: Exception) { output.delete(); throw e }
        finally { password.fill('\u0000') }
    }
    /** Restores a backup encrypted under [passphrase]; that passphrase becomes the restored vault's recovery passphrase. */
    suspend fun stage(uri: Uri, passphrase: CharArray, progress: (String) -> Unit): Int = repository.access { current ->
        val canonical = Passphrase.normalize(String(passphrase))
        val password = canonical.toCharArray()
        try { return@access stageWith(current, uri, canonical, password, progress) } finally { password.fill('\u0000') }
    }
    private suspend fun stageWith(current: Vault, uri: Uri, canonical: String, password: CharArray, progress: (String) -> Unit): Int {
        val operation = currentCoroutineContext()
        discard()
        val staged = repository.staging()
        return try {
            var manifest: BackupManifest? = null
            context.contentResolver.openInputStream(uri)!!.buffered().use { encrypted ->
                BackupCodec.decrypt(encrypted, password) { clear ->
                    val zip = ZipInputStream(clear)
                    require(zip.nextEntry?.name == "manifest.json") { "Missing manifest" }
                    val manifestBytes = readBounded(zip, 16 * 1024 * 1024)
                    val data = documentJson.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                    validate(data); manifest = data
                    val requiredBytes = data.attachments.fold(0L) { total, attachment -> Math.addExact(total, attachment.size) }
                    require(requiredBytes <= android.os.StatFs(staged.directory.absolutePath).availableBytes) { "Insufficient storage" }
                    val expected = data.attachments.associateBy { it.id }
                    val seen = mutableSetOf<String>()
                    while (true) {
                        checkAccess(); operation.ensureActive()
                        val entry = zip.nextEntry ?: break
                        require(entry.name.startsWith("attachments/")) { "Unexpected archive entry" }
                        val id = entry.name.removePrefix("attachments/")
                        val attachment = expected[id] ?: error("Unexpected attachment")
                        require(seen.add(id)) { "Duplicate attachment" }
                        progress("Validating attachment ${seen.size} of ${expected.size}")
                        val (size, hash) = staged.write(id, zip, attachment.size) { operation.ensureActive() }
                        require(size == attachment.size && hash == attachment.sha256) { "Attachment integrity failure" }
                    }
                    require(seen == expected.keys) { "Missing attachments" }
                }
            }
            val data = checkNotNull(manifest)
            val validTags = data.tags.map { it.id }.toSet()
            data.tags.forEach { staged.dao.insertTag(TagRow.of(it)) }
            data.attachments.forEach { staged.dao.putAttachment(it.copy(indexed = false, nextPage = 0)) }
            data.notes.forEach { repository.saveIn(staged, it, validTags) }
            staged.setNoteOrder(data.orderedIds())
            // The user just typed this passphrase, so it counts as confirmed.
            repository.setPassphrase(staged, canonical, confirmed = true)
            // The automatic backup destination belongs to this device, not to the restored content.
            AutoBackup.DEVICE_SECRETS.forEach { name -> current.dao.secret(name)?.let { staged.dao.putSecret(SecretRow(name, it)) } }
            checkAccess(); operation.ensureActive(); pending = staged
            data.notes.size
        } catch (e: Exception) { staged.close(); staged.directory.deleteRecursively(); throw e }
    }
    suspend fun commit() = repository.access {
        val staged = checkNotNull(pending)
        pending = null // Claim ownership before lock/cancel UI can discard the pending stage.
        try { repository.activate(staged) }
        catch (e: Exception) {
            if (repository.vault !== staged) { staged.close(); staged.directory.deleteRecursively() }
            throw e
        }
    }
    fun discard() { pending?.let { it.close(); it.directory.deleteRecursively() }; pending = null }
    fun copyChecked(input: InputStream, output: OutputStream, checkpoint: () -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        try { while (true) { checkAccess(); checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) } }
        finally { buffer.fill(0) }
    }
    companion object {
        fun readBounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= limit) { "Manifest too large" }; out.write(buffer, 0, count) }
            return out.toByteArray()
        }
        fun validate(data: BackupManifest) {
            require(data.version in 1..3)
            if (data.version < 3) require(data.tags.isEmpty() && data.notes.all { it.tagIds.isEmpty() })
            require(data.tags.map { it.id }.distinct().size == data.tags.size)
            require(data.tags.map { tagKey(it.name) }.distinct().size == data.tags.size)
            data.tags.forEach { require(validId(it.id) && it.name.isNotEmpty() && tagName(it.name) == it.name) }
            val tagIds = data.tags.map { it.id }.toSet()
            if (data.version == 1) require(data.sort != null)
            else require(data.order != null && data.order.size == data.notes.size && data.order.toSet() == data.notes.map(Note::id).toSet())
            require(data.notes.map { it.id }.distinct().size == data.notes.size)
            require(data.attachments.map { it.id }.distinct().size == data.attachments.size)
            val attachments = data.attachments.associateBy { it.id }
            val referenced = mutableSetOf<String>()
            data.notes.forEach { note ->
                require(validId(note.id)); note.document.validate()
                require(note.tagIds.distinct().size == note.tagIds.size && tagIds.containsAll(note.tagIds))
                note.document.attachments.forEach { id ->
                    require(attachments[id]?.noteId == note.id && referenced.add(id))
                }
            }
            require(referenced == attachments.keys)
            data.attachments.forEach { require(validId(it.id) && it.size >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        }
    }
}
