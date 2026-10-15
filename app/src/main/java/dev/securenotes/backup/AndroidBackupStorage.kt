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
    companion object {
        const val KEY = "backup-configuration-v1"
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
            val bytes = documentJson.encodeToString(configuration).toByteArray(Charsets.UTF_8)
            vault.database.withTransaction { vault.dao.putSecret(SecretRow(KEY, bytes)) }
        }
    }
}

class AndroidBackupFiles(context: Context) : BackupFiles {
    private val resolver = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    override fun hasGrant(folder: String) = resolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission && it.isWritePermission }
    override fun hasAnyGrant(folder: String) = resolver.persistedUriPermissions.any { it.uri.toString() == folder }
    override fun retain(folder: String) { resolver.takePersistableUriPermission(folder.toUri(), flags) }
    override fun release(folder: String) { resolver.releasePersistableUriPermission(folder.toUri(), flags) }
    override fun create(folder: String, name: String): String {
        val tree = folder.toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name) ?: throw IOException()
        return uri.toString()
    }
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
