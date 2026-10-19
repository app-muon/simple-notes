package dev.securenotes.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce

/** Tokens are in-memory identities; a delayed callback can never recreate an invalidated selection. */
class BackupSetupToken internal constructor()
data class BackupRequest(val admission: BackupAdmission, val job: Job? = null, val token: BackupSetupToken? = null)
data class BackupSchedulingState(val selecting: Boolean = false, val setupQueued: Boolean = false, val working: Boolean = false) {
    val mutationsBlocked: Boolean get() = selecting || setupQueued || working
}

/** Main-thread lifecycle/admission/service ownership; storage decisions belong to [AutoBackup]. */
@OptIn(FlowPreview::class)
class BackupScheduler(
    private val scope: CoroutineScope,
    private val backup: AutoBackup,
    private val changes: StateFlow<Long>,
    private val unlocked: () -> Boolean,
    private val pendingEdits: () -> Boolean,
    private val flush: suspend () -> Unit,
    private val hold: () -> (() -> Unit),
    private val flushFailed: () -> Unit,
) {
    private var visible = false
    private var watcher: Job? = null
    private var changesWatcher: Job? = null
    private var grantRefresh: Job? = null
    private var automatic: Job? = null
    private var explicit: Job? = null
    private var leavingFlush: Job? = null
    private var checking: Job? = null
    private val operations = mutableSetOf<Job>()
    private val releases = mutableMapOf<Job, () -> Unit>()
    private val holdEligible = mutableSetOf<Job>()
    private var reservation: BackupSetupToken? = null
    private data class Selection(val folder: String, val progress: (String) -> Unit)
    private var queued: Selection? = null
    private var deferredAutomatic = false
    private var deferredFingerprint = false
    private val mutableState = MutableStateFlow(BackupSchedulingState())
    val state = mutableState.asStateFlow()
    private fun publish() { mutableState.value = BackupSchedulingState(reservation != null, queued != null, operations.isNotEmpty()) }
    fun admission(): BackupAdmission {
        val coordinator = backup.admission()
        if (!coordinator.accepted) return coordinator
        return if (reservation != null || operations.isNotEmpty()) BackupAdmission.BUSY else BackupAdmission.ACCEPTED
    }
    fun canStartMutation() = admission().accepted
    fun onForeground() {
        visible = true
        if (!unlocked()) return
        // Activity results can precede onResume. Own the service before executing even the first flush.
        if (queued != null) startSetup()
        holdEligible.toList().forEach(::acquireHold)
        refreshThenTrigger()
        drainDeferred()
    }
    fun onLeaving() {
        // A full check runs only while visible. Its cancellation finishes inside the coordinator lock, which the leaving
        // save then waits for, so it is no longer the explicit operation that would defer that save.
        checking?.let { check -> check.cancel(); checking = null; if (explicit === check) explicit = null }
        if (unlocked()) {
            if (reservation != null || explicit != null) {
                deferredAutomatic = true
                if (leavingFlush == null) launchOwned(false, register = { leavingFlush = it }) { flushSafely() }
            } else trigger(flushEvenIfDisabled = true)
        }
        visible = false
    }
    fun onUnlocked() {
        watcher?.cancel()
        watcher = scope.launch {
            backup.refresh()
            backup.status.collect { status ->
                val watch = status?.configuration?.mode == BackupMode.PAIR && !status.restartRequired
                if (watch && changesWatcher == null) {
                    changesWatcher = launch {
                        launch { changes.debounce(1_000).collect { refreshFingerprint() } }
                        launch { changes.debounce(AutoBackup.QUIET_MILLIS).collect { refreshThenTrigger() } }
                    }
                    refreshThenTrigger()
                } else if (!watch) { changesWatcher?.cancel(); changesWatcher = null }
            }
        }
    }
    fun onLocked() {
        watcher?.cancel(); watcher = null; changesWatcher?.cancel(); changesWatcher = null
        grantRefresh?.cancel(); grantRefresh = null
        reservation = null; queued = null
        deferredAutomatic = false; deferredFingerprint = false
        operations.toList().forEach { it.cancel() }
        backup.clearStatus(); publish()
    }
    private fun acquireHold(job: Job) {
        if (visible && job !in releases && !job.isCompleted) releases[job] = hold()
    }
    /** Ownership and completion handlers precede start, including Main.immediate and pre-body cancellation. */
    private fun launchOwned(likely: Boolean, register: (Job) -> Unit = {}, completed: () -> Unit = {}, action: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (_: CancellationException) { /* Non-cancellable ciphertext completion is owned by the coordinator. */ }
            catch (_: Exception) { /* Coordinator or flush publishes the error. */ }
        }
        operations += job; register(job)
        if (likely) { holdEligible += job; acquireHold(job) }
        job.invokeOnCompletion {
            releases.remove(job)?.invoke(); operations.remove(job); holdEligible.remove(job)
            if (automatic === job) automatic = null
            if (explicit === job) explicit = null
            if (leavingFlush === job) leavingFlush = null
            if (checking === job) checking = null
            completed(); publish(); drainDeferred()
        }
        publish(); job.start()
        return job
    }
    private suspend fun flushSafely(): Boolean = try { flush(); true }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { flushFailed(); false }
    private fun refreshThenTrigger() {
        if (!unlocked()) return
        if (reservation != null || explicit != null) { deferredAutomatic = true; return }
        if (grantRefresh != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            backup.refreshGrant()
            // A provider response cannot authorize starting a foreground service after onPause.
            if (visible || releases.isNotEmpty()) trigger() else deferredAutomatic = true
        }
        grantRefresh = job
        job.invokeOnCompletion { if (grantRefresh === job) grantRefresh = null }
        job.start()
    }
    private fun refreshFingerprint() {
        if (reservation != null || operations.isNotEmpty() || !visible) { deferredFingerprint = true; return }
        launchOwned(false) { backup.refreshFingerprint() }
    }
    private fun drainDeferred() {
        if (!unlocked() || !visible || reservation != null || operations.isNotEmpty()) return
        if (deferredAutomatic) { deferredAutomatic = false; refreshThenTrigger() }
        if (deferredFingerprint) { deferredFingerprint = false; refreshFingerprint() }
    }
    fun trigger(flushEvenIfDisabled: Boolean = false) {
        if (!unlocked()) return
        if (reservation != null || explicit != null || automatic != null || leavingFlush != null) { deferredAutomatic = true; return }
        if (!visible && releases.isEmpty()) { deferredAutomatic = true; return }
        val likely = backup.mayWrite(pendingEdits())
        if (!likely && !flushEvenIfDisabled) return
        launchOwned(likely, register = { automatic = it }) {
            runAttempts()
        }
    }
    /** Keep the same job and service hold across edits/pauses arriving after snapshot capture. */
    private suspend fun runAttempts(force: Boolean = false, progress: (String) -> Unit = {}) {
        var forceAttempt = force
        do {
            deferredAutomatic = false
            // A pause must flush even when an earlier attempt made backup retry ineligible.
            if (!flushSafely()) return
            backup.run(force = forceAttempt, progress = progress)
            forceAttempt = false
            if (!deferredAutomatic || !unlocked() || reservation != null) return
            if (!visible && currentCoroutineContext()[Job] !in releases) return
            // This may suspend; the original job still owns its hold throughout the refresh.
            backup.refreshGrant()
        } while (true)
    }
    fun manual(progress: (String) -> Unit = {}): BackupRequest {
        val admission = admission()
        if (!admission.accepted) return BackupRequest(admission)
        if (!backup.mayWrite(pendingEdits(), force = true)) { refreshThenTrigger(); return BackupRequest(BackupAdmission.INELIGIBLE) }
        return BackupRequest(BackupAdmission.ACCEPTED, launchOwned(true, register = { explicit = it }) {
            runAttempts(force = true, progress = progress)
        })
    }
    /** Reads and checks every file of the pair, without a service hold: leaving the app cancels it. */
    fun verify(progress: (String) -> Unit = {}): BackupRequest {
        val admission = admission()
        if (!admission.accepted) return BackupRequest(admission)
        if (!visible) return BackupRequest(BackupAdmission.BUSY)
        return BackupRequest(BackupAdmission.ACCEPTED, launchOwned(false, register = { explicit = it; checking = it }) { backup.verify(progress) })
    }
    /** Refresh permission eligibility without starting a service or retrying storage implicitly. */
    fun refreshAccess(): BackupRequest {
        val admission = admission()
        if (!admission.accepted) return BackupRequest(admission)
        return BackupRequest(BackupAdmission.ACCEPTED, launchOwned(false, register = { explicit = it }) { backup.refreshGrant() })
    }
    fun beginSetup(): BackupRequest {
        val admission = admission()
        if (!admission.accepted) return BackupRequest(backup.rejectSetup(admission))
        if (backup.setupNeedsReview()) return BackupRequest(backup.rejectSetup(BackupAdmission.PHRASE_REVIEW_REQUIRED))
        val token = BackupSetupToken()
        reservation = token; backup.clearSetupError(); publish()
        return BackupRequest(BackupAdmission.ACCEPTED, token = token)
    }
    fun isCurrent(token: BackupSetupToken?) = token != null && reservation === token
    fun submitSetup(token: BackupSetupToken?, folder: String, progress: (String) -> Unit = {}): BackupRequest {
        if (!isCurrent(token)) return BackupRequest(backup.rejectSetup(BackupAdmission.SELECT_AGAIN))
        if (queued != null || explicit != null) return BackupRequest(backup.rejectSetup(BackupAdmission.BUSY))
        queued = Selection(folder, progress); publish()
        return if (visible) startSetup() else BackupRequest(BackupAdmission.ACCEPTED)
    }
    private fun startSetup(): BackupRequest {
        val selection = queued ?: return BackupRequest(BackupAdmission.SELECT_AGAIN)
        val token = reservation ?: return BackupRequest(backup.rejectSetup(BackupAdmission.SELECT_AGAIN))
        queued = null
        val admission = backup.admission()
        if (!admission.accepted || backup.setupNeedsReview()) {
            val reason = if (!admission.accepted) admission else BackupAdmission.PHRASE_REVIEW_REQUIRED
            backup.rejectSetup(reason); cancelSetup(token); return BackupRequest(reason)
        }
        return BackupRequest(BackupAdmission.ACCEPTED, launchOwned(true, register = { explicit = it }, completed = {
            if (reservation === token) { reservation = null; queued = null }
        }) {
            if (!flushSafely()) backup.setupFlushFailed()
            else if (isCurrent(token)) {
                val result = backup.choose(selection.folder, selection.progress)
                if (result.accepted) deferredAutomatic = true
            }
        })
    }
    fun cancelSetup(token: BackupSetupToken?) {
        if (!isCurrent(token)) return
        reservation = null; queued = null; explicit?.cancel(); publish(); drainDeferred()
    }
    fun disable(): BackupRequest {
        val admission = admission()
        if (!admission.accepted) return BackupRequest(admission)
        return BackupRequest(BackupAdmission.ACCEPTED, launchOwned(false, register = { explicit = it }) { backup.turnOff() })
    }
}
