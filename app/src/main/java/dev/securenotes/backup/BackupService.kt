package dev.securenotes.backup

import android.content.Context
import android.net.Uri
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.storage.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable data class BackupManifest(val version: Int = 1, val notes: List<Note>, val attachments: List<AttachmentRow>, val sort: SortOrder)

class BackupService(private val context: Context, private val repository: NotesRepository, private val checkAccess: () -> Unit) {
    suspend fun checkHeader(uri: Uri) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        checkAccess()
        context.contentResolver.openInputStream(uri)!!.use(BackupCodec::validateHeader)
    }
    private val cache = File(context.cacheDir, "backups").apply { mkdirs() }
    @Volatile var pending: Vault? = null
        private set
    suspend fun configured(): Boolean = repository.access { it.dao.secret("backup-password") != null }
    suspend fun configure(password: CharArray) = repository.access { setPassword(it, password) }
    private suspend fun setPassword(v: Vault, password: CharArray) {
        require(password.isNotEmpty())
        val salt = Crypto.random(32)
        val key = Crypto.derive(password, salt)
        try { v.dao.putSecret(SecretRow("backup-password", salt + Crypto.hash(key))) } finally { key.fill(0) }
    }
    suspend fun create(password: CharArray, progress: (String) -> Unit): File = repository.access { v ->
        val operation = currentCoroutineContext()
        val verifier = v.dao.secret("backup-password") ?: error("Configure a backup password first")
        val key = Crypto.derive(password, verifier.copyOfRange(0, 32))
        try { require(MessageDigest.isEqual(Crypto.hash(key), verifier.copyOfRange(32, verifier.size))) { "Incorrect backup password" } } finally { key.fill(0) }
        val notes = v.dao.notes().map { it.decode() }
        val ids = notes.flatMap { it.document.blocks.mapNotNull { b -> b.attachmentId } }.toSet()
        val attachments = v.dao.attachments().filter { it.id in ids }
        val manifest = BackupManifest(notes = notes, attachments = attachments.map { it.copy(indexed = false, nextPage = 0) }, sort = v.sort())
        val output = File(cache, "${newId()}.ssnb")
        try {
            output.outputStream().buffered().use { encrypted -> BackupCodec.encrypt(encrypted, password) { clear ->
                val zip = ZipOutputStream(clear)
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(documentJson.encodeToString(manifest).toByteArray()); zip.closeEntry()
                attachments.forEachIndexed { index, a ->
                    checkAccess(); operation.ensureActive(); progress("Encrypting attachment ${index + 1} of ${attachments.size}")
                    zip.putNextEntry(ZipEntry("attachments/${a.id}"))
                    v.read(a.id).use { copyChecked(it, zip) { operation.ensureActive() } }; zip.closeEntry()
                }
                zip.finish(); zip.flush()
            } }
            output
        } catch (e: Exception) { output.delete(); throw e }
    }
    suspend fun stage(uri: Uri, password: CharArray, progress: (String) -> Unit): Int = repository.access {
        val operation = currentCoroutineContext()
        discard()
        val staged = repository.staging()
        try {
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
            data.attachments.forEach { staged.dao.putAttachment(it.copy(indexed = false, nextPage = 0)) }
            data.notes.forEach { repository.saveIn(staged, it) }
            staged.setSort(data.sort); setPassword(staged, password)
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
            require(data.version == 1)
            require(data.notes.map { it.id }.distinct().size == data.notes.size)
            require(data.attachments.map { it.id }.distinct().size == data.attachments.size)
            val blockIds = data.notes.flatMap { it.document.blocks.map { b -> b.id } }
            require(blockIds.distinct().size == blockIds.size)
            val attachments = data.attachments.associateBy { it.id }
            val referenced = mutableSetOf<String>()
            data.notes.forEach { note ->
                require(validId(note.id)); note.document.validate()
                note.document.blocks.mapNotNull { it.attachmentId }.forEach { id ->
                    require(attachments[id]?.noteId == note.id); referenced.add(id)
                }
            }
            require(referenced == attachments.keys)
            data.attachments.forEach { require(validId(it.id) && it.size >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        }
    }
}
