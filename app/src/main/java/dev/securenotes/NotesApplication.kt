package dev.securenotes

import android.app.Application
import android.app.KeyguardManager
import android.content.*
import dev.securenotes.backup.AutoBackup
import dev.securenotes.backup.BackupService
import dev.securenotes.security.DeviceKeys
import dev.securenotes.security.Passphrase
import dev.securenotes.security.ResumeSession
import dev.securenotes.storage.NotesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException

class NotesApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val unlocked = MutableStateFlow(false)
    lateinit var repository: NotesRepository
        private set
    lateinit var keys: DeviceKeys
        private set
    lateinit var camera: dev.securenotes.storage.CameraCapture
        private set
    lateinit var backups: BackupService
        private set
    lateinit var autoBackup: AutoBackup
        private set
    private var backupJob: Job? = null
    @Volatile private var authorized = false
    private var indexing: Job? = null
    private var closing: Job? = null
    private var screenOff = false
    private lateinit var resumeSession: ResumeSession
    private var resumeExpiry: Job? = null
    private val sessionMutex = Mutex()
    private val sessionEpoch = java.util.concurrent.atomic.AtomicLong()
    var authenticating = false
        private set
    var authenticationEpoch = 0L
        private set
    fun beginAuthentication(): Boolean {
        if (authenticating || unlocked.value) return false
        authenticating = true; authenticationEpoch = sessionEpoch.get(); return true
    }
    fun endAuthentication() { authenticating = false }
    /** A root recovered from the passphrase, held only until the new device envelope is written or abandoned. */
    var pendingRecovery: ByteArray? = null
        private set
    fun beginRecovery(root: ByteArray) { clearRecovery(); pendingRecovery = root }
    fun clearRecovery() { pendingRecovery?.fill(0); pendingRecovery = null }
    val grants: MutableSet<android.net.Uri> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        keys = DeviceKeys(this)
        resumeSession = ResumeSession(android.os.SystemClock::elapsedRealtime, keys::sealResume, keys::openResume)
        repository = NotesRepository(this, ::checkAccess)
        camera = dev.securenotes.storage.CameraCapture(this, repository)
        backups = BackupService(this, repository, ::checkAccess)
        autoBackup = AutoBackup(this, repository, backups)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> { screenOff = true; lock() }
                    Intent.ACTION_SCREEN_ON -> { if (getSystemService(KeyguardManager::class.java).isDeviceLocked) lock() }
                    Intent.ACTION_USER_PRESENT -> { if (screenOff) lock(); screenOff = false }
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        java.io.File(cacheDir, "backups").listFiles()?.forEach { it.delete() }
    }
    fun checkAccess() {
        if (!authorized || getSystemService(KeyguardManager::class.java).isDeviceLocked) throw CancellationException("Authentication required")
    }
    suspend fun unlock(root: ByteArray, expectedEpoch: Long = sessionEpoch.get(), authenticatedAt: Long? = android.os.SystemClock.elapsedRealtime()) {
        try {
            closing?.join()
            sessionMutex.withLock {
                if (unlocked.value) return@withLock
                if (expectedEpoch != sessionEpoch.get()) throw CancellationException("Session changed")
                authorized = true
                try {
                    repository.open(root)
                    if (expectedEpoch != sessionEpoch.get()) throw CancellationException("Session changed")
                    checkAccess()
                    if (authenticatedAt == null) {
                        if (resumeSession.remainingMillis() == 0L) throw CancellationException("Resume expired")
                    } else {
                        // Failure of this convenience feature must not prevent a normal authenticated unlock.
                        withContext(Dispatchers.IO) { runCatching { resumeSession.remember(root, authenticatedAt) }.onFailure { resumeSession.clear() } }
                        if (expectedEpoch != sessionEpoch.get()) { resumeSession.clear(); throw CancellationException("Session changed") }
                        checkAccess()
                        resumeExpiry?.cancel()
                        resumeExpiry = scope.launch { delay(resumeSession.remainingMillis()); resumeSession.clearIfExpired() }
                    }
                    // Every vault gets a recovery passphrase before any note is shown. If that write fails (e.g. a full
                    // disk), the notes still open so the user can free space; setup is retried on the next unlock.
                    try { repository.ensurePassphrase { Passphrase.generate(Passphrase.wordlist(this@NotesApplication)) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { repository.issue.value = "Your recovery passphrase could not be set up, probably because storage is full. Free some space; Notes will try again the next time you unlock." }
                    // Setup suspends for key derivation and storage. A lock can revoke this session while it runs.
                    if (expectedEpoch != sessionEpoch.get()) throw CancellationException("Session changed")
                    checkAccess()
                    if (authenticatedAt == null && resumeSession.remainingMillis() == 0L) throw CancellationException("Resume expired")
                    unlocked.value = true; screenOff = false; startIndexing(); startAutoBackup()
                } catch (e: Exception) {
                    authorized = false; unlocked.value = false; repository.close(); throw e
                }
            }
        } finally { root.fill(0) }
    }
    suspend fun tryResume(expectedEpoch: Long = sessionEpoch.get()): Boolean {
        var recovered: ByteArray? = null
        return try {
            withContext(Dispatchers.IO) {
                recovered = resumeSession.resume(getSystemService(KeyguardManager::class.java).let { it.isDeviceSecure && !it.isDeviceLocked })
            }
            val root = recovered ?: return false
            unlock(root, expectedEpoch, authenticatedAt = null); unlocked.value
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { resumeSession.clear(); false }
        finally { recovered?.fill(0) }
    }
    fun authenticationIsCurrent() = authenticationEpoch == sessionEpoch.get()
    fun checkDevice() { if (screenOff || getSystemService(KeyguardManager::class.java).isDeviceLocked) lock() }
    fun lock() {
        if (!authorized) return
        sessionEpoch.incrementAndGet(); repository.freezeEdits()
        authorized = false; unlocked.value = false; indexing?.cancel(); backupJob?.cancel(); autoBackup.clearStatus(); revokeGrants()
        closing = scope.launch { sessionMutex.withLock { repository.close() } }
    }
    /** Flushes edits, then refreshes the automatic backup if content changed (backgrounding is a natural checkpoint). */
    fun flushEdits() { if (unlocked.value) scope.launch {
        try { repository.flush() } catch (_: CancellationException) { return@launch } catch (_: Exception) { repository.issue.value = "Recent changes could not be saved. Free storage and retry."; return@launch }
        try { autoBackup.run() } catch (_: Exception) { /* Locked or recorded in the backup status. */ }
    } }
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startAutoBackup() {
        backupJob?.cancel()
        // Each attempt is isolated so one failure never stops later changes from being backed up.
        suspend fun attempt() { try { autoBackup.run() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Shown in the backup status. */ } }
        backupJob = scope.launch {
            try {
                attempt()
                repository.changes.debounce(AutoBackup.QUIET_MILLIS).collect { attempt() }
            } catch (_: CancellationException) { /* Locked. */ }
        }
    }
    fun startIndexing() {
        if (indexing?.isActive == true) return
        indexing = scope.launch {
            try { while (unlocked.value && repository.indexNext()) { yield() } } catch (_: Exception) { /* Durable checkpoints retry after unlock/import. */ }
        }
    }
    fun revokeGrants() { grants.forEach { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; grants.clear() }
}
