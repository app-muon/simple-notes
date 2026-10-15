package dev.securenotes.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Serializes destination mutations. Only verified ciphertext is ever published as active. */
class AutoBackup(
    private val files: BackupFiles,
    private val settings: BackupSettingsStore,
    private val source: BackupSource,
    private val unlocked: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object { const val QUIET_MILLIS = 60_000L }
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow<BackupStatus?>(null)
    val status = mutableStatus.asStateFlow()
    @Volatile private var trusted: BackupConfiguration? = null
    @Volatile private var operation: String? = null
    private var setupError: BackupError? = null
    @Volatile private var transientError: BackupError? = null
    @Volatile private var restartRequired = false
    private var visible = false
    @Volatile private var savedRevision: Long? = null
    private var reviewedPhrase: String? = null
    @Volatile private var inspection: BackupInspection? = null
    // Revisions are process-local counters. Never trust a revision decoded from a previous process.
    @Volatile private var oversizedRevision: Long? = null
    @Volatile private var grantAvailable: Boolean? = null
    val busy: Boolean get() = mutex.isLocked
    fun admission(): BackupAdmission = when {
        !unlocked() -> BackupAdmission.LOCKED
        restartRequired -> BackupAdmission.RESTART_REQUIRED
        busy -> BackupAdmission.BUSY
        trusted == null -> BackupAdmission.UNAVAILABLE
        else -> BackupAdmission.ACCEPTED
    }
    fun canMutate() = admission().accepted
    fun rejectSetup(reason: BackupAdmission): BackupAdmission {
        setupError = BackupError(when (reason) {
            BackupAdmission.RESTART_REQUIRED -> BackupProblem.RESTART_REQUIRED
            BackupAdmission.SELECT_AGAIN, BackupAdmission.LOCKED -> BackupProblem.SELECT_AGAIN
            BackupAdmission.PHRASE_REVIEW_REQUIRED -> BackupProblem.PHRASE_CHANGED
            else -> BackupProblem.BUSY
        }, "setup-admission")
        publish(); return reason
    }
    fun clearSetupError() { setupError = null; publish() }
    fun setupFlushFailed() { setupError = BackupError(BackupProblem.IO, "flush"); publish() }
    @Synchronized fun clearStatus() { visible = false; reviewedPhrase = null; mutableStatus.value = null }
    @Synchronized private fun publish() {
        mutableStatus.value = if (visible && unlocked()) BackupStatus(trusted ?: BackupConfiguration(), operation, transientError ?: trusted?.error, setupError, restartRequired, grantAvailable) else null
    }
    suspend fun refresh() = mutex.withLock {
        visible = unlocked()
        if (!restartRequired) try { trusted = settings.load().also { it.validate() }; transientError = null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { restartRequired = true; transientError = BackupError(BackupProblem.INVALID_CONFIGURATION, "load") }
        refreshGrant()
        publish()
    }
    /** The only cached eligibility input requiring a provider; never call it from composition. */
    suspend fun refreshGrant() {
        val folder = trusted?.grant
        val available = if (folder == null) null else withContext(io) {
            try { files.hasGrant(folder) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }
        if (trusted?.grant == folder) { grantAvailable = available; publish() }
    }
    private suspend fun requireGrant(folder: String) = withContext(io) {
        try {
            val available = files.hasGrant(folder)
            if (trusted?.grant == folder) grantAvailable = available
            if (!available) throw BackupFailure(BackupProblem.ACCESS)
        } catch (e: Exception) {
            if (e.hasSecurityCause() && trusted?.grant == folder) grantAvailable = false
            throw e
        }
    }
    /** The caller enforces fresh authentication. Approval applies only to this session and phrase. */
    fun phraseReviewed(hash: String) { if (unlocked()) reviewedPhrase = hash }
    fun setupNeedsReview() = status.value?.error?.problem == BackupProblem.PHRASE_CHANGED && reviewedPhrase == null
    private fun eligible(config: BackupConfiguration, force: Boolean, pendingEdits: Boolean = false): Boolean {
        if (!unlocked() || restartRequired || config.mode != BackupMode.PAIR) return false
        return when ((transientError ?: config.error)?.problem) {
            BackupProblem.ACCESS -> grantAvailable == true
            BackupProblem.MANIFEST_TOO_LARGE -> pendingEdits || oversizedRevision != source.revision
            BackupProblem.PHRASE_CHANGED, BackupProblem.PHRASE_MISSING, BackupProblem.ACTIVE_DAMAGED,
            BackupProblem.INVALID_CONFIGURATION, BackupProblem.RESTART_REQUIRED -> false
            else -> force || pendingEdits || config.pending || config.error != null || transientError != null || savedRevision != source.revision
        }
    }
    fun mayWrite(pendingEdits: Boolean = false, force: Boolean = false): Boolean {
        val config = trusted ?: return false
        if (operation != null) return true
        if (!eligible(config, force, pendingEdits)) return false
        return force || pendingEdits || inspection?.takeIf { it.revision == source.revision }?.empty != true
    }
    /** Metadata only: no attachment stream or password derivation. */
    suspend fun refreshFingerprint() {
        if (!canMutate() || trusted?.mode != BackupMode.PAIR) return
        mutex.withLock {
            try {
                inspection = source.inspect()
                val config = checkNotNull(trusted)
                if (!config.pending && config.error == null && transientError == null && inspection?.fingerprint == config.fingerprint) savedRevision = inspection?.revision
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The normal attempt reports source failures. */ }
        }
    }
    private suspend fun persist(next: BackupConfiguration, began: () -> Unit = {}) {
        var started = false
        try {
            settings.save(next) { started = true; began() }
            trusted = next; transientError = null
        } catch (e: Exception) {
            if (started) { restartRequired = true; transientError = BackupError(BackupProblem.RESTART_REQUIRED, "record", files.diagnostic(e)); publish() }
            throw e
        }
    }
    private fun error(e: Exception, step: String): BackupError = BackupError(when {
        e.hasSecurityCause() -> BackupProblem.ACCESS
        e is BackupFailure -> e.problem
        e is ManifestTooLarge -> BackupProblem.MANIFEST_TOO_LARGE
        else -> BackupProblem.IO
    }, step, files.diagnostic(e))

    suspend fun run(force: Boolean = false, progress: (String) -> Unit = {}) = mutex.withLock {
        val config = trusted ?: return@withLock
        if (!eligible(config, force)) return@withLock
        var step = "inspect"
        operation = step; publish()
        fun report(value: String) { step = value; operation = value; publish() }
        try {
            config.validate()
            requireGrant(checkNotNull(config.folder))
            val metadata = source.inspect().also { inspection = it }
            if (metadata.phraseHash != config.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
            if (!force && metadata.empty) return@withLock
            if (!force && !config.pending && config.error == null && transientError == null && metadata.fingerprint == config.fingerprint) {
                savedRevision = metadata.revision; return@withLock
            }
            // Fail before encryption/KDF work for an unreadable or already damaged active file.
            withContext(io) { report("verify-active"); requireGrant(checkNotNull(config.folder)); files.verify(config.slots[config.active]) }
            report("prepare")
            source.prepare(progress).use { artifact ->
                if (artifact.content.phraseHash != config.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
                if (!force && artifact.content.empty) return@withLock
                // The provider may have changed while encryption ran. Verify again just before writing.
                withContext(io) { report("verify-active"); requireGrant(checkNotNull(config.folder)); files.verify(config.slots[config.active]) }
                // Lock before the write begins is cancellation, not an uncertain commit.
                report("record-pending"); persist(config.copy(pending = true))
                withContext(NonCancellable + io) {
                    val inactive = 1 - config.active
                    report("access"); requireGrant(checkNotNull(config.folder))
                    files.writeVerified(config.slots[inactive].uri, artifact, ::report)
                    val slots = config.slots.toMutableList().also { it[inactive] = it[inactive].copy(length = artifact.length, sha256 = artifact.sha256) }
                    report("record-success")
                    persist(config.copy(slots = slots, active = inactive, fingerprint = artifact.content.fingerprint, savedAt = clock(), pending = false, error = null))
                    savedRevision = artifact.content.revision; oversizedRevision = null
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            savedRevision = null
            if (e is ManifestTooLarge) oversizedRevision = e.revision
            if (!restartRequired) {
                // Inactive read-back failure retries; damaged active evidence requires a new pair.
                val failure = error(e, step).let { if (it.problem == BackupProblem.ACTIVE_DAMAGED && step != "verify-active") it.copy(problem = BackupProblem.IO) else it }
                if (failure.problem == BackupProblem.ACCESS) grantAvailable = false
                transientError = failure
                try { persist(checkNotNull(trusted).copy(error = failure)) }
                catch (_: CancellationException) { /* Durable pending survives a lock. */ }
                catch (_: Exception) { /* persist set the restart block. */ }
            }
        } finally { operation = null; publish() }
    }

    /** Reject delayed picker callbacks when busy. Never adopt a document by name. */
    suspend fun choose(folder: String, progress: (String) -> Unit = {}): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return rejectSetup(admission)
        if (!mutex.tryLock()) return rejectSetup(BackupAdmission.BUSY)
        val old = checkNotNull(trusted)
        val created = mutableListOf<String>()
        var hadGrant = true
        var commitStarted = false
        var committed = false
        var step = "prepare"
        operation = "setup"; setupError = null; publish()
        try {
            if (!unlocked()) return rejectSetup(BackupAdmission.LOCKED)
            if (restartRequired) return rejectSetup(BackupAdmission.RESTART_REQUIRED)
            source.prepare(progress).use { artifact ->
                if (old.mode == BackupMode.PAIR && old.phraseHash != artifact.content.phraseHash && reviewedPhrase != artifact.content.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
                currentCoroutineContext().ensureActive()
                if (!unlocked()) throw CancellationException()
                withContext(io) {
                    hadGrant = files.hasAnyGrant(folder)
                    files.retain(folder)
                    requireGrant(folder)
                }
                val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(clock()))
                val slots = withContext(io) {
                    listOf("A", "B").map { label ->
                        currentCoroutineContext().ensureActive()
                        step = "create-$label"
                        requireGrant(folder)
                        val requested = "Notes-$timestamp-$label.ssnb"
                        val uri = files.create(folder, requested)
                        if (uri.isBlank() || uri in old.managedUris || uri in created) throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
                        created += uri
                        val document = files.document(uri, requested)
                        files.writeVerified(document.uri, artifact) { step = it; operation = "setup-$it"; publish() }
                        BackupSlot(document.uri, document.name, artifact.length, artifact.sha256, document.nameConfirmed)
                    }
                }
                val next = BackupConfiguration(mode = BackupMode.PAIR, folder = folder, slots = slots, active = 1,
                    phraseHash = artifact.content.phraseHash, fingerprint = artifact.content.fingerprint, savedAt = clock())
                next.validate(); step = "record-setup"
                persist(next) { commitStarted = true }
                committed = true; savedRevision = artifact.content.revision; oversizedRevision = null; reviewedPhrase = null; grantAvailable = true
            }
            return BackupAdmission.ACCEPTED
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setupError = error(e, step); return BackupAdmission.FAILED }
        finally {
            withContext(NonCancellable + io) {
                if (!commitStarted) {
                    created.forEach { runCatching { files.delete(it) } }
                    if (!hadGrant && folder != old.grant) runCatching { files.release(folder) }
                } else if (committed) old.grant?.takeIf { it != folder }?.let { runCatching { files.release(it) } }
            }
            operation = null; publish(); mutex.unlock()
        }
    }
    suspend fun turnOff(): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return admission
        if (!mutex.tryLock()) return BackupAdmission.BUSY
        try {
            val old = checkNotNull(trusted)
            persist(BackupConfiguration())
            withContext(NonCancellable + io) { old.grant?.let { runCatching { files.release(it) } } }
            savedRevision = null; oversizedRevision = null; setupError = null; grantAvailable = null
            return BackupAdmission.ACCEPTED
        } finally { publish(); mutex.unlock() }
    }
    /** Lock order: coordinator -> repository. Staging never holds the coordinator lock. */
    suspend fun publishRestore(phraseHash: String, publishVault: suspend (BackupConfiguration, () -> Unit) -> Unit) = mutex.withLock {
        val latest = settings.load().also { it.validate() }
        val next = latest.afterRestore(phraseHash)
        operation = "restore"; publish()
        var began = false
        try {
            publishVault(next) { began = true }
            trusted = next; savedRevision = null; oversizedRevision = null; inspection = null; reviewedPhrase = null
            if (!restartRequired) transientError = null
        } catch (e: Exception) {
            if (began) { restartRequired = true; transientError = BackupError(BackupProblem.RESTART_REQUIRED, "restore-publication", files.diagnostic(e)) }
            throw e
        } finally { operation = null; publish() }
    }
}
