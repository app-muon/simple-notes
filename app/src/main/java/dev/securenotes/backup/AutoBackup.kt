package dev.securenotes.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.securenotes.storage.NotesRepository
import dev.securenotes.storage.SecretRow
import dev.securenotes.storage.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps one user-chosen document (any Storage Access Framework provider, e.g. Dropbox) overwritten with the latest
 * passphrase-encrypted backup. The provider app performs any upload; this app still has no network access.
 */
class AutoBackup(private val context: Context, private val repository: NotesRepository, private val backups: BackupService) {
    companion object {
        private const val URI = "backup-uri"
        private const val NAME = "backup-name"
        private const val FINGERPRINT = "backup-fingerprint"
        private const val SAVED_AT = "backup-saved-at"
        private const val ERROR = "backup-error"
        /** Destination settings follow the device across a restore; status does not. */
        val DEVICE_SECRETS = listOf(URI, NAME)
        const val QUIET_MILLIS = 60_000L
        private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
    data class Status(val location: String?, val savedAt: Long?, val error: String?)
    private class LostPermission : Exception()
    private class IncompleteWrite : Exception()
    val status = MutableStateFlow<Status?>(null)
    private val running = Mutex()
    /** True when [uri] already holds a Secure Notes backup, which choosing it as the destination would overwrite. */
    suspend fun containsBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try { context.contentResolver.openInputStream(uri)?.use { BackupCodec.validateHeader(it); true } ?: false }
        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    private suspend fun Vault.text(name: String) = dao.secret(name)?.toString(Charsets.UTF_8)
    private suspend fun Vault.put(name: String, value: String) = dao.putSecret(SecretRow(name, value.toByteArray(Charsets.UTF_8)))
    suspend fun refresh() {
        status.value = repository.access { v -> Status(v.text(NAME), v.text(SAVED_AT)?.toLongOrNull(), v.text(ERROR)) }
    }
    fun clearStatus() { status.value = null }
    /** [uri] comes from ACTION_CREATE_DOCUMENT; the grant must be persistable so later backups need no picker. */
    suspend fun choose(uri: Uri) {
        try { context.contentResolver.takePersistableUriPermission(uri, FLAGS) }
        catch (_: SecurityException) { throw IllegalStateException("That location does not allow repeated saving") }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "Backup file"
        try {
            repository.access { v ->
                v.text(URI)?.let(Uri::parse)?.takeIf { it != uri }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, FLAGS) } }
                v.put(URI, uri.toString()); v.put(NAME, name.take(256))
                listOf(FINGERPRINT, SAVED_AT, ERROR).forEach { v.dao.deleteSecret(it) }
            }
        } catch (e: Exception) { runCatching { context.contentResolver.releasePersistableUriPermission(uri, FLAGS) }; throw e }
        run(force = true)
    }
    suspend fun turnOff() {
        repository.access { v ->
            v.text(URI)?.let(Uri::parse)?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, FLAGS) } }
            (DEVICE_SECRETS + listOf(FINGERPRINT, SAVED_AT, ERROR)).forEach { v.dao.deleteSecret(it) }
        }
        refresh()
    }
    /** Writes a new backup when content changed since the last successful one (or always, when [force]). */
    suspend fun run(force: Boolean = false, progress: (String) -> Unit = {}) {
        running.withLock {
            val (uri, last) = repository.access { v -> v.text(URI)?.let(Uri::parse) to v.text(FINGERPRINT) }
            var unrecorded: String? = null
            if (uri != null) try {
                if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) throw LostPermission()
                val created = backups.create(progress, if (force) null else last)
                if (created != null) {
                    val (file, fingerprint) = created
                    // The file is already ciphertext, so finishing the copy after a lock leaks nothing and avoids a truncated backup.
                    try { withContext(NonCancellable + Dispatchers.IO) { write(uri, file) } } finally { file.delete() }
                    repository.access { v -> v.put(FINGERPRINT, fingerprint); v.put(SAVED_AT, System.currentTimeMillis().toString()); v.dao.deleteSecret(ERROR) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is LostPermission, is SecurityException -> "Secure Notes can no longer write to the backup file. Choose the backup location again."
                    is IncompleteWrite -> "This backup location didn't replace the previous backup cleanly, so the file may not restore. Choose a different backup file or location."
                    else -> "The latest automatic backup could not be saved. Check the backup location and available storage."
                }
                // Recording the failure can itself fail (for example on a full disk); then it is shown from memory.
                try { repository.access { v -> v.put(ERROR, message) } }
                catch (c: CancellationException) { throw c } catch (_: Exception) { unrecorded = message }
            }
            refresh()
            unrecorded?.let { message -> status.value = (status.value ?: Status(null, null, null)).copy(error = message) }
        }
    }
    private fun write(uri: Uri, file: File) {
        val resolver = context.contentResolver
        // Prefer modes that truncate. Some providers only accept plain "w", which may not truncate, so check the result.
        val output = listOf("wt", "rwt").firstNotNullOfOrNull { mode -> runCatching { resolver.openOutputStream(uri, mode) }.getOrNull() }
            ?: resolver.openOutputStream(uri, "w")
        checkNotNull(output).use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
        // Old bytes left after a shorter backup would make the file fail authentication on restore.
        val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (size >= 0 && size != file.length()) throw IncompleteWrite()
    }
}
