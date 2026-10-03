package dev.securenotes

import android.app.Application
import android.app.KeyguardManager
import android.content.*
import dev.securenotes.security.DeviceKeys
import dev.securenotes.storage.NotesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
    @Volatile private var authorized = false
    private var indexing: Job? = null
    private var closing: Job? = null
    private var screenOff = false
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
    val grants: MutableSet<android.net.Uri> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        keys = DeviceKeys(this)
        repository = NotesRepository(this, ::checkAccess)
        camera = dev.securenotes.storage.CameraCapture(this, repository)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> { screenOff = true; if (getSystemService(KeyguardManager::class.java).isDeviceLocked) lock() }
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
    suspend fun unlock(root: ByteArray, expectedEpoch: Long = sessionEpoch.get()) {
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
                    unlocked.value = true; screenOff = false; startIndexing()
                } catch (e: Exception) {
                    authorized = false; unlocked.value = false; repository.close(); throw e
                }
            }
        } finally { root.fill(0) }
    }
    fun checkDevice() { if (screenOff || getSystemService(KeyguardManager::class.java).isDeviceLocked) lock() }
    fun lock() {
        if (!authorized) return
        sessionEpoch.incrementAndGet(); repository.freezeEdits()
        authorized = false; unlocked.value = false; indexing?.cancel(); revokeGrants()
        closing = scope.launch { sessionMutex.withLock { repository.close() } }
    }
    fun flushEdits() { if (unlocked.value) scope.launch { try { repository.flush() } catch (_: CancellationException) { } catch (_: Exception) { repository.issue.value = "Recent changes could not be saved. Free storage and retry." } } }
    fun startIndexing() {
        if (indexing?.isActive == true) return
        indexing = scope.launch {
            try { while (unlocked.value && repository.indexNext()) { yield() } } catch (_: Exception) { /* Durable checkpoints retry after unlock/import. */ }
        }
    }
    fun revokeGrants() { grants.forEach { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; grants.clear() }
}
