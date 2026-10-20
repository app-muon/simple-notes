package dev.securenotes.backup

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.security.Passphrase
import dev.securenotes.storage.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Versions 1–3 contain their attachments. Version 4 is an index: its attachments are separate files in [attachmentFolder],
 * named by [attachmentFiles] and encrypted under [attachmentKey]. Version-4 fields are omitted from earlier versions, so
 * their bytes and content fingerprints are unchanged.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class BackupManifest(
    val version: Int = 3, val notes: List<Note>, val attachments: List<AttachmentRow>,
    val sort: SortOrder? = null, val order: List<String>? = null,
    val tags: List<Tag> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val attachmentKey: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val attachmentFolder: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val attachmentFiles: Map<String, String>? = null,
) {
    fun orderedIds(): List<String> = if (version == 1) sortedNotes(notes, checkNotNull(sort)).map(Note::id) else checkNotNull(order)
}
/** A version-4 index was authenticated; its attachments need read access to the folder that holds them. */
class AttachmentFolderNeeded(val folder: String?) : Exception()

class BackupService(private val context: Context, private val repository: NotesRepository, private val checkAccess: () -> Unit) : BackupSource {
    override val revision: Long get() = repository.changes.value
    var coordinator: AutoBackup? = null
    data class Counts(val notes: Int, val attachments: Int, val tags: Int)
    var pendingCounts: Counts? = null
        private set
    suspend fun checkHeader(uri: Uri) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        checkAccess()
        context.contentResolver.openInputStream(uri)!!.use(BackupCodec::validateHeader)
    }
    private val cache = File(context.cacheDir, "backups").apply { mkdirs() }
    @Volatile var pending: Vault? = null
        private set
    /** An authenticated version-4 index and the phrase that opened it, until its attachments are staged or discarded. */
    private class Waiting(val manifest: BackupManifest, val phrase: String)
    @Volatile private var waiting: Waiting? = null
    val awaitingFolder: Boolean get() = waiting != null
    private data class Snapshot(val manifest: BackupManifest, val bytes: ByteArray, val content: BackupInspection, val phrase: String)
    private suspend fun snapshot(v: Vault, requireConfirmed: Boolean): Snapshot {
        val capturedRevision = revision
        val phrase = v.passphrase()?.let(Passphrase::normalize)?.takeIf { it.isNotBlank() } ?: throw BackupFailure(BackupProblem.PHRASE_MISSING)
        if (requireConfirmed && !v.passphraseConfirmed()) throw BackupFailure(BackupProblem.PHRASE_MISSING)
        val manifest = v.database.withTransaction {
            val notes = v.orderedNotes()
            val ids = notes.flatMap { it.document.attachments }.toSet()
            BackupManifest(notes = notes.sortedBy { it.id }.map { it.copy(tagIds = it.tagIds.sorted()) },
                attachments = v.dao.attachments().filter { it.id in ids }.sortedBy { it.id }.map { it.copy(indexed = false, nextPage = 0) },
                order = notes.map(Note::id), tags = v.dao.tags().map { it.decode() }.sortedBy { it.id })
        }
        val bytes = documentJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        checkManifestSize(bytes.size, capturedRevision)
        val hash = phraseHash(phrase)
        val digest = java.security.MessageDigest.getInstance("SHA-256").apply { update(bytes); update(hash.toByteArray(Charsets.UTF_8)) }.digest().hex()
        return Snapshot(manifest, bytes, BackupInspection(digest, hash, capturedRevision, manifest.notes.isEmpty() && manifest.tags.isEmpty()), phrase)
    }
    override suspend fun inspect(): BackupInspection = repository.access { snapshot(it, true).content }
    override suspend fun prepare(progress: (String) -> Unit): PreparedBackup {
        var artifact: PreparedBackup? = null
        try { return repository.access { v -> snapshot(v, true).let { encrypt(v, it.bytes, it.manifest.attachments, it.phrase, it.content, progress) }.also { result -> artifact = result } } }
        catch (e: Exception) { artifact?.close(); throw e }
    }
    override suspend fun capture(progress: (String) -> Unit): BackupSnapshot = repository.access { v ->
        val snapshot = snapshot(v, true)
        BackupSnapshot(snapshot.content, snapshot.manifest.attachments.map { BackupAttachment(it.id, it.size, it.sha256) }, snapshot.manifest)
    }
    // The repository mutex keeps the blob from being deleted while it is read.
    override suspend fun prepareAttachment(snapshot: BackupSnapshot, attachment: BackupAttachment, key: ByteArray): PreparedBackup = repository.access { v ->
        val operation = currentCoroutineContext()
        val row = v.dao.attachment(attachment.id)
        if (row == null || row.size != attachment.size || row.sha256 != attachment.sha256) throw SnapshotChanged()
        val output = File(cache, "${newId()}.ssna")
        try {
            checkAccess(); operation.ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            output.outputStream().buffered().use { encrypted -> AttachmentCodec.encrypt(encrypted, key, attachment.id) { clear ->
                val measured = object : FilterOutputStream(clear) {
                    override fun write(value: Int) { out.write(value); digest.update(value.toByte()); size++ }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); digest.update(bytes, offset, length); size += length }
                }
                v.read(attachment.id).use { copyChecked(it, measured) { operation.ensureActive() } }
            } }
            // A vault blob that no longer matches its row must never reach a backup.
            if (size != attachment.size || digest.digest().hex() != attachment.sha256) throw IOException("Attachment integrity failure")
            checkAccess(); operation.ensureActive()
            PreparedBackup(output, snapshot.content, output.length(), output.inputStream().use(::ciphertextDigest))
        } catch (e: Exception) { output.delete(); throw e }
    }
    override suspend fun prepareIndex(snapshot: BackupSnapshot, key: ByteArray, folder: String?, files: Map<String, String>): PreparedBackup = repository.access { v ->
        val phrase = v.passphrase()?.let(Passphrase::normalize)?.takeIf { it.isNotBlank() } ?: throw BackupFailure(BackupProblem.PHRASE_MISSING)
        if (phraseHash(phrase) != snapshot.content.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
        val bytes = indexBytes(snapshot.manifest as BackupManifest, key, folder, files, snapshot.content.revision)
        encrypt(v, bytes, emptyList(), phrase, snapshot.content) {}
    }
    // Only the phrase needs the vault; reading never holds the repository mutex, so edits keep saving meanwhile.
    override suspend fun checkIndex(input: InputStream): CheckedIndex {
        val phrase = repository.access { it.passphrase() }?.let(Passphrase::normalize)?.takeIf { it.isNotBlank() } ?: throw BackupFailure(BackupProblem.PHRASE_MISSING)
        val password = phrase.toCharArray()
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val operation = currentCoroutineContext()
                val data = BackupCodec.decrypt(input.buffered(), password) { clear ->
                    val zip = ZipInputStream(clear)
                    require(zip.nextEntry?.name == "manifest.json") { "Missing manifest" }
                    val data = documentJson.decodeFromString<BackupManifest>(readBounded(zip, MANIFEST_LIMIT).toString(Charsets.UTF_8))
                    validate(data)
                    if (data.version == 4) require(zip.nextEntry == null) { "Unexpected archive entry" }
                    else {
                        val expected = data.attachments.associateBy { it.id }
                        val seen = mutableSetOf<String>()
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            require(entry.name.startsWith("attachments/")) { "Unexpected archive entry" }
                            val attachment = expected[entry.name.removePrefix("attachments/")] ?: error("Unexpected attachment")
                            require(seen.add(attachment.id)) { "Duplicate attachment" }
                            val (size, hash) = measure(zip, attachment.size) { checkAccess(); operation.ensureActive() }
                            require(size == attachment.size && hash == attachment.sha256) { "Attachment integrity failure" }
                        }
                        require(seen == expected.keys) { "Missing attachments" }
                    }
                    data
                }
                // Parsed only after the whole file has been authenticated.
                CheckedIndex(data.attachments.map { BackupAttachment(it.id, it.size, it.sha256) }, data.attachmentFiles,
                    if (data.version == 4) BackupCodec.decodeKey(checkNotNull(data.attachmentKey)) else null)
            }
        } finally { password.fill('\u0000') }
    }
    override suspend fun checkAttachment(input: InputStream, key: ByteArray, attachment: BackupAttachment) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val operation = currentCoroutineContext()
        AttachmentCodec.decrypt(input.buffered(), key, attachment.id) { clear ->
            val (size, hash) = measure(clear, attachment.size) { checkAccess(); operation.ensureActive() }
            require(size == attachment.size && hash == attachment.sha256) { "Attachment integrity failure" }
        }
    }
    /** Test fixtures deliberately bypass phrase confirmation; production [prepare] always requires it. */
    @VisibleForTesting
    internal suspend fun create(progress: (String) -> Unit, unchangedSince: String? = null): Pair<File, String>? = repository.access { v ->
        val snapshot = snapshot(v, false)
        if (snapshot.content.fingerprint == unchangedSince) null
        else encrypt(v, snapshot.bytes, snapshot.manifest.attachments, snapshot.phrase, snapshot.content, progress).let { it.file to it.content.fingerprint }
    }
    // The repository mutex protects blobs from deletion; the metadata transaction has already ended.
    private suspend fun encrypt(v: Vault, manifest: ByteArray, attachments: List<AttachmentRow>, phrase: String, content: BackupInspection, progress: (String) -> Unit): PreparedBackup {
        val operation = currentCoroutineContext()
        val password = phrase.toCharArray()
        val output = File(cache, "${newId()}.ssnb")
        return try {
            checkAccess(); operation.ensureActive()
            output.outputStream().buffered().use { encrypted -> BackupCodec.encrypt(encrypted, password) { clear ->
                val zip = ZipOutputStream(clear)
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                attachments.forEachIndexed { index, a ->
                    checkAccess(); operation.ensureActive(); progress("Encrypting attachment ${index + 1} of ${attachments.size}")
                    zip.putNextEntry(ZipEntry("attachments/${a.id}"))
                    v.read(a.id).use { copyChecked(it, zip) { operation.ensureActive() } }; zip.closeEntry()
                }
                zip.finish(); zip.flush()
            } }
            checkAccess(); operation.ensureActive()
            PreparedBackup(output, content, output.length(), output.inputStream().use(::ciphertextDigest))
        } catch (e: Exception) { output.delete(); throw e }
        finally { password.fill('\u0000') }
    }
    /**
     * Restores a backup encrypted under [passphrase]; that passphrase becomes the restored vault's recovery passphrase.
     * A version-4 index with attachments throws [AttachmentFolderNeeded] once authenticated; continue with [stageFolder].
     */
    suspend fun stage(uri: Uri, passphrase: CharArray, progress: (String) -> Unit): Int = repository.access {
        val canonical = Passphrase.normalize(String(passphrase))
        val password = canonical.toCharArray()
        try { return@access stageWith(uri, canonical, password, progress) } finally { password.fill('\u0000') }
    }
    private suspend fun stageWith(uri: Uri, canonical: String, password: CharArray, progress: (String) -> Unit): Int {
        val operation = currentCoroutineContext()
        discard()
        val staged = repository.staging()
        var handedOff = false
        return try {
            var manifest: BackupManifest? = null
            context.contentResolver.openInputStream(uri)!!.buffered().use { encrypted ->
                BackupCodec.decrypt(encrypted, password) { clear ->
                    val zip = ZipInputStream(clear)
                    require(zip.nextEntry?.name == "manifest.json") { "Missing manifest" }
                    val manifestBytes = readBounded(zip, MANIFEST_LIMIT)
                    val data = documentJson.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                    validate(data); manifest = data
                    if (data.version == 4) require(zip.nextEntry == null) { "Unexpected archive entry" }
                    else {
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
            }
            val data = checkNotNull(manifest)
            if (data.version == 4) {
                // Parsed only now, after the whole index has been authenticated.
                BackupCodec.decodeKey(checkNotNull(data.attachmentKey)).fill(0)
                if (data.attachments.isNotEmpty()) {
                    staged.close(); staged.directory.deleteRecursively(); handedOff = true
                    waiting = Waiting(data, canonical)
                    throw AttachmentFolderNeeded(data.attachmentFolder)
                }
            }
            publishStaged(staged, data, canonical)
        } catch (e: Exception) { if (!handedOff) { staged.close(); staged.directory.deleteRecursively() }; throw e }
    }
    /**
     * Stages the attachments of the index authenticated by [stage] from [folder]: the folder holding the recorded
     * attachment directory, or that directory itself, wherever it now is. Identity comes from authentication, not names.
     */
    suspend fun stageFolder(folder: AttachmentFolder, progress: (String) -> Unit): Int = repository.access {
        val operation = currentCoroutineContext()
        val restoring = checkNotNull(waiting) { "No restore is waiting for attachments" }
        val data = restoring.manifest
        val names = checkNotNull(data.attachmentFiles)
        val staged = repository.staging()
        try {
            val key = BackupCodec.decodeKey(checkNotNull(data.attachmentKey))
            try {
                val requiredBytes = data.attachments.fold(0L) { total, attachment -> Math.addExact(total, attachment.size) }
                require(requiredBytes <= android.os.StatFs(staged.directory.absolutePath).availableBytes) { "Insufficient storage" }
                val top = folder.list(null)
                val directory = top.filter { it.directory && it.name == data.attachmentFolder }.singleOrNull()
                val entries = (if (directory != null) folder.list(directory) else top).filter { !it.directory }
                data.attachments.forEachIndexed { index, attachment ->
                    checkAccess(); operation.ensureActive()
                    progress("Validating attachment ${index + 1} of ${data.attachments.size}")
                    val entry = attachmentEntry(entries, { it.name }, attachment.id, names.getValue(attachment.id)) ?: throw FileNotFoundException("Missing attachment")
                    folder.open(entry).buffered().use { input ->
                        AttachmentCodec.decrypt(input, key, attachment.id) { clear ->
                            val (size, hash) = staged.write(attachment.id, clear, attachment.size) { operation.ensureActive() }
                            require(size == attachment.size && hash == attachment.sha256) { "Attachment integrity failure" }
                        }
                    }
                }
            } finally { key.fill(0) }
            publishStaged(staged, data, restoring.phrase).also { waiting = null }
        } catch (e: Exception) { staged.close(); staged.directory.deleteRecursively(); throw e }
    }
    private suspend fun publishStaged(staged: Vault, data: BackupManifest, canonical: String): Int {
        val validTags = data.tags.map { it.id }.toSet()
        data.tags.forEach { staged.dao.insertTag(TagRow.of(it)) }
        data.attachments.forEach { staged.dao.putAttachment(it.copy(indexed = false, nextPage = 0)) }
        data.notes.forEach { repository.saveIn(staged, it, validTags) }
        staged.setNoteOrder(data.orderedIds())
        // Either confirmed device words or explicitly entered words decrypted this backup.
        repository.setPassphrase(staged, canonical, confirmed = true)
        checkAccess(); currentCoroutineContext().ensureActive(); pending = staged
        pendingCounts = Counts(data.notes.size, data.attachments.size, data.tags.size)
        return data.notes.size
    }
    suspend fun commit() {
        val hash = repository.access { phraseHash(checkNotNull(checkNotNull(pending).passphrase())) }
        suspend fun publish(configuration: BackupConfiguration?, beginWrite: () -> Unit = {}) = repository.access { current ->
            val staged = checkNotNull(pending)
            pending = null; pendingCounts = null
            try {
                beginWrite()
                val next = configuration ?: VaultBackupSettings.read(current).afterRestore(hash)
                VaultBackupSettings.write(staged, next)
                listOfNotNull(next.pairTag, next.setup?.pairTag).forEach { VaultBackupSettings.copyPair(current, staged, it) }
                repository.activate(staged)
            } catch (e: Exception) {
                if (repository.vault !== staged) { staged.close(); staged.directory.deleteRecursively() }
                throw e
            }
        }
        val coordinator = coordinator
        if (coordinator != null) coordinator.publishRestore(hash) { config, begin -> publish(config, begin) } else publish(null)
    }
    fun discard() { pending?.let { it.close(); it.directory.deleteRecursively() }; pending = null; pendingCounts = null; waiting = null }
    fun copyChecked(input: InputStream, output: OutputStream, checkpoint: () -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        try { while (true) { checkAccess(); checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) } }
        finally { buffer.fill(0) }
    }
    companion object {
        const val MANIFEST_LIMIT = 16 * 1024 * 1024
        fun checkManifestSize(size: Int, revision: Long) { if (size > MANIFEST_LIMIT) throw ManifestTooLarge(revision) }
        fun phraseHash(phrase: String) = Crypto.hash(Passphrase.normalize(phrase).toByteArray(Charsets.UTF_8)).hex()
        fun readBounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= limit) { "Manifest too large" }; out.write(buffer, 0, count) }
            return out.toByteArray()
        }
        /** Length and SHA-256 of [input] read to its end, failing once it exceeds [maxBytes]. */
        fun measure(input: InputStream, maxBytes: Long, checkpoint: () -> Unit = {}): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            try {
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= maxBytes - size) { "Attachment length mismatch" }
                    digest.update(buffer, 0, count); size += count
                }
            } finally { buffer.fill(0) }
            return size to digest.digest().hex()
        }
        /** A single path component a provider can show; names of one manifest must not collide ignoring case. */
        fun safeName(name: String) = name.isNotBlank() && name.length <= 255 && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
        /**
         * How restore finds an attachment's file among a folder's files: the mapped name must match exactly one; failing
         * that, one whose name starts with the ID. Saves and full checks use the same rule, so they look where restore will.
         */
        fun <T> attachmentEntry(files: List<T>, nameOf: (T) -> String, id: String, name: String): T? {
            val named = files.filter { nameOf(it) == name }
            if (named.isNotEmpty()) return named.singleOrNull()
            return files.filter { nameOf(it).startsWith(id) }.singleOrNull()
        }
        /** The serialized version-4 index for [content]; the size limit applies to all of it, including the new fields. */
        fun indexBytes(content: BackupManifest, key: ByteArray, folder: String?, files: Map<String, String>, revision: Long): ByteArray {
            val manifest = content.copy(version = 4, attachmentKey = BackupCodec.encodeKey(key), attachmentFolder = folder, attachmentFiles = files)
            validateAttachmentFiles(manifest)
            return documentJson.encodeToString(manifest).toByteArray(Charsets.UTF_8).also { checkManifestSize(it.size, revision) }
        }
        fun validateAttachmentFiles(data: BackupManifest) {
            require(!data.attachmentKey.isNullOrEmpty()) { "Missing attachment key" }
            val files = requireNotNull(data.attachmentFiles) { "Missing attachment files" }
            require(files.keys == data.attachments.map { it.id }.toSet()) { "Attachment files do not match" }
            require(files.values.all(::safeName) && files.values.map { it.lowercase() }.distinct().size == files.size) { "Unsafe attachment file name" }
            data.attachmentFolder?.let { require(safeName(it)) { "Unsafe attachment folder name" } }
            if (data.attachments.isNotEmpty()) requireNotNull(data.attachmentFolder) { "Missing attachment folder" }
        }
        fun validate(data: BackupManifest) {
            require(data.version in 1..4)
            if (data.version < 3) require(data.tags.isEmpty() && data.notes.all { it.tagIds.isEmpty() })
            if (data.version < 4) require(data.attachmentKey == null && data.attachmentFolder == null && data.attachmentFiles == null)
            else validateAttachmentFiles(data)
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
