package dev.securenotes.backup

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import dev.securenotes.document.documentJson
import dev.securenotes.storage.NotesRepository
import dev.securenotes.storage.SecretRow
import dev.securenotes.storage.Vault
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException

class VaultBackupSettings(private val repository: NotesRepository) : BackupSettingsStore {
    override suspend fun load() = repository.access { read(it) }
    override suspend fun save(configuration: BackupConfiguration, beginWrite: () -> Unit) = repository.access { vault ->
        // No suspension/cancellation between the commit and returning its acknowledged result.
        withContext(NonCancellable) { beginWrite(); write(vault, configuration) }
    }
    override suspend fun key(pairTag: String): ByteArray? = repository.access { it.dao.secret(keyName(pairTag)) }
    override suspend fun saveKey(pairTag: String, key: ByteArray, beginWrite: () -> Unit) = repository.access { vault ->
        withContext(NonCancellable) { beginWrite(); vault.dao.putSecret(SecretRow(keyName(pairTag), key.copyOf())) }
    }
    override suspend fun files(pairTag: String): Map<String, AttachmentFile> = repository.access { vault ->
        val prefix = filePrefix(pairTag)
        vault.dao.secrets(prefix).associate { row ->
            val file = documentJson.decodeFromString<AttachmentFile>(row.value.toString(Charsets.UTF_8))
            require(row.name == prefix + file.id && dev.securenotes.document.validId(file.id)) { "Invalid attachment record" }
            file.id to file
        }
    }
    override suspend fun saveFile(pairTag: String, file: AttachmentFile, beginWrite: () -> Unit) = repository.access { vault ->
        val bytes = documentJson.encodeToString(file).toByteArray(Charsets.UTF_8)
        withContext(NonCancellable) { beginWrite(); vault.dao.putSecret(SecretRow(filePrefix(pairTag) + file.id, bytes)) }
    }
    override suspend fun forget(pairTag: String) = repository.access { vault ->
        vault.database.withTransaction { vault.dao.deleteSecret(keyName(pairTag)); vault.dao.deleteSecrets(filePrefix(pairTag)) }
    }
    companion object {
        const val KEY = "backup-configuration-v1"
        private fun keyName(pairTag: String) = "backup-attachment-key:$pairTag"
        private fun filePrefix(pairTag: String) = "backup-file:$pairTag:"
        /** Restore publishes a new vault generation; the pair's key and file records move with its configuration. */
        suspend fun copyPair(from: Vault, to: Vault, pairTag: String) {
            val rows = listOfNotNull(from.dao.secret(keyName(pairTag))?.let { SecretRow(keyName(pairTag), it) }) + from.dao.secrets(filePrefix(pairTag))
            to.database.withTransaction { rows.forEach { to.dao.putSecret(it) } }
        }
        suspend fun read(vault: Vault): BackupConfiguration {
            vault.dao.secret(KEY)?.let { return documentJson.decodeFromString<BackupConfiguration>(it.toString(Charsets.UTF_8)) }
            fun ByteArray?.text() = this?.toString(Charsets.UTF_8)
            val uri = vault.dao.secret("backup-uri").text() ?: return BackupConfiguration()
            return BackupConfiguration(mode = BackupMode.LEGACY, legacyUri = uri,
                legacyName = vault.dao.secret("backup-name").text() ?: "Backup file",
                savedAt = vault.dao.secret("backup-saved-at").text()?.toLongOrNull())
        }
        suspend fun write(vault: Vault, configuration: BackupConfiguration) {
            configuration.validate()
            val bytes = documentJson.encodeToString(configuration.copy(version = 2)).toByteArray(Charsets.UTF_8)
            vault.database.withTransaction { vault.dao.putSecret(SecretRow(KEY, bytes)) }
        }
    }
}

class AndroidBackupFiles(private val context: Context) : BackupFiles {
    private val resolver = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    override fun hasGrant(folder: String) = resolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission && it.isWritePermission }
    override fun hasAnyGrant(folder: String) = resolver.persistedUriPermissions.any { it.uri.toString() == folder }
    override fun retain(folder: String) { resolver.takePersistableUriPermission(folder.toUri(), flags) }
    override fun release(folder: String) { resolver.releasePersistableUriPermission(folder.toUri(), flags) }
    /** A granted tree's own document, or a directory document created under it. */
    private fun parent(folder: String): android.net.Uri {
        val uri = folder.toUri()
        return if (DocumentsContract.isDocumentUri(context, uri)) uri else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }
    override fun create(folder: String, name: String): String =
        (DocumentsContract.createDocument(resolver, parent(folder), "application/octet-stream", name) ?: throw IOException()).toString()
    override fun createDirectory(folder: String, name: String): String =
        (DocumentsContract.createDocument(resolver, parent(folder), DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException()).toString()
    override fun children(directory: String): List<BackupChild> {
        val uri = directory.toUri()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri)), columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(BackupChild(DocumentsContract.buildDocumentUriUsingTree(uri, c.getString(0)).toString(), c.getString(1) ?: "", if (c.isNull(2)) null else c.getLong(2))) }
        } ?: throw IOException()
    }
    override fun measure(uri: String): Long? = (resolver.openFileDescriptor(uri.toUri(), "r") ?: throw IOException()).use { it.statSize.takeIf { size -> size >= 0 } }
    override fun displayName(uri: String): String? = resolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    override fun delete(uri: String) { check(DocumentsContract.deleteDocument(resolver, uri.toUri())) }
    override fun openRead(uri: String) = resolver.openInputStream(uri.toUri()) ?: throw IOException()
    override fun openWrite(uri: String, mode: String): BackupOutput {
        val descriptor = resolver.openFileDescriptor(uri.toUri(), mode) ?: throw IOException()
        return object : BackupOutput {
            override val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            override fun truncate(length: Long) { Os.ftruncate(descriptor.fileDescriptor, length) }
            override fun sync() {
                try { Os.fsync(descriptor.fileDescriptor) }
                catch (e: ErrnoException) {
                    // fsync(2): pipes/special descriptors may not support synchronization. EIO/ENOSPC
                    // and all other errors remain failures. Read-back verification is still mandatory.
                    if (!unsupportedBackupSync(e.errno)) throw e
                }
            }
            override fun close() { stream.close() }
        }
    }
    override fun diagnostic(error: Throwable): String = error.causeChain().take(5).joinToString(" / ") {
        it.javaClass.simpleName + ((it as? ErrnoException)?.let { e -> " " + (OsConstants.errnoName(e.errno) ?: "UNKNOWN_ERRNO") } ?: "")
    }
}
internal fun unsupportedBackupSync(errno: Int) = errno in setOf(OsConstants.EINVAL, OsConstants.ENOTSUP, OsConstants.ESPIPE)

/** A folder chosen during restore to supply a version-4 backup's attachment files. */
interface AttachmentFolder {
    data class Entry(val id: String, val name: String, val directory: Boolean)
    /** Direct children of [directory], or of the chosen folder itself when null. */
    fun list(directory: Entry?): List<Entry>
    fun open(entry: Entry): java.io.InputStream
}
/** Read access to a tree from the folder picker, held only for this restore. */
class DocumentTreeFolder(private val resolver: android.content.ContentResolver, private val tree: android.net.Uri) : AttachmentFolder {
    override fun list(directory: AttachmentFolder.Entry?): List<AttachmentFolder.Entry> {
        val parent = directory?.id ?: DocumentsContract.getTreeDocumentId(tree)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(AttachmentFolder.Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)) }
        } ?: throw IOException()
    }
    override fun open(entry: AttachmentFolder.Entry) = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)) ?: throw IOException()
}
