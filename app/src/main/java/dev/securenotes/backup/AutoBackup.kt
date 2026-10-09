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
    /** The [NotesRepository.changes] count the destination is known to hold; null when unknown. */
    @Volatile private var savedChange: Long? = null
    /** False when the destination already holds the current content, so leaving the app needs nothing kept running. */
    fun mayWrite(pendingEdits: Boolean) = status.value?.location != null && (pendingEdits || repository.changes.value != savedChange)
    /** True when [uri] already holds a Notes backup, which choosing it as the destination would overwrite. */
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
            val change = repository.changes.value
            val (uri, last) = repository.access { v -> v.text(URI)?.let(Uri::parse) to v.text(FINGERPRINT) }
            var unrecorded: String? = null
            // Shown with unexpected failures so a provider problem can be diagnosed without device logs.
            var step = "encrypt"
            if (uri != null) try {
                if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) throw LostPermission()
                val created = backups.create(progress, if (force) null else last)
                if (created != null) {
                    val (file, fingerprint) = created
                    // The file is already ciphertext, so finishing the copy after a lock leaks nothing and avoids a truncated backup.
                    try { withContext(NonCancellable + Dispatchers.IO) { write(uri, file) { step = it } } } finally { file.delete() }
                    step = "record"
                    repository.access { v -> v.put(FINGERPRINT, fingerprint); v.put(SAVED_AT, System.currentTimeMillis().toString()); v.dao.deleteSecret(ERROR) }
                }
                savedChange = change
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                savedChange = null
                val message = when (e) {
                    is LostPermission, is SecurityException -> "Notes can no longer write to the backup file. Choose the backup location again."
                    is IncompleteWrite -> "This backup location didn't replace the previous backup cleanly, so the file may not restore. Choose a different backup file or location."
                    // Exception types only: messages can contain file names or URIs.
                    else -> "The latest automatic backup could not be saved. Check the backup location and available storage. " +
                        "(Failed at $step: ${e.javaClass.simpleName}${e.cause?.let { " / " + it.javaClass.simpleName } ?: ""})"
                }
                // Recording the failure can itself fail (for example on a full disk); then it is shown from memory.
                try { repository.access { v -> v.put(ERROR, message) } }
                catch (c: CancellationException) { throw c } catch (_: Exception) { unrecorded = message }
            }
            refresh()
            unrecorded?.let { message -> status.value = (status.value ?: Status(null, null, null)).copy(error = message) }
        }
    }
    private fun write(uri: Uri, file: File, step: (String) -> Unit) {
        val resolver = context.contentResolver
        // Prefer modes that truncate; accepting one is the provider's promise to replace the old backup. Reading back is then
        // skipped because providers that upload after close (e.g. Dropbox) still report the previous copy's size.
        step("open")
        val (mode, output) = listOf("wt", "rwt").firstNotNullOfOrNull { m -> runCatching { resolver.openOutputStream(uri, m) }.getOrNull()?.let { m to it } }
            ?: ("w" to checkNotNull(resolver.openOutputStream(uri, "w")))
        step("write $mode")
        output.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) }; step("close $mode") }
        if (mode != "w") return
        step("verify")
        // Plain "w" may not truncate, and old bytes left after a shorter backup would make the file fail authentication on restore.
        val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        if (size >= 0 && size != file.length()) throw IncompleteWrite()
    }
}
