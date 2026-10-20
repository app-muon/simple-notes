package dev.securenotes.backup

import dev.securenotes.document.newId
import dev.securenotes.security.Crypto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
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
    @Volatile private var checkError: BackupError? = null
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
    @Synchronized fun clearStatus() { visible = false; reviewedPhrase = null; checkError = null; mutableStatus.value = null }
    @Synchronized private fun publish() {
        mutableStatus.value = if (visible && unlocked()) BackupStatus(trusted ?: BackupConfiguration(), operation, transientError ?: trusted?.error, setupError, restartRequired, grantAvailable, checkError) else null
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
    fun setupNeedsReview() = reviewedPhrase == null &&
        (status.value?.error?.problem == BackupProblem.PHRASE_CHANGED || setupError?.problem == BackupProblem.PHRASE_CHANGED)
    private fun eligible(config: BackupConfiguration, force: Boolean, pendingEdits: Boolean = false): Boolean {
        if (!unlocked() || restartRequired || config.mode != BackupMode.PAIR) return false
        return when ((transientError ?: config.error)?.problem) {
            BackupProblem.ACCESS -> grantAvailable == true
            BackupProblem.MANIFEST_TOO_LARGE -> pendingEdits || oversizedRevision != source.revision
            BackupProblem.PHRASE_CHANGED, BackupProblem.PHRASE_MISSING, BackupProblem.ACTIVE_DAMAGED, BackupProblem.ATTACHMENT_DAMAGED,
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
    /** Commits a key or attachment record. Like [persist], an uncertain outcome blocks backup mutations until restart. */
    private suspend fun record(write: suspend (() -> Unit) -> Unit) {
        var started = false
        try { write { started = true } }
        catch (e: Exception) {
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
    private fun timestamp(): String = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(clock()))

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
            val tag = config.pairTag
            val recorded = tag?.let { settings.files(it) }
            if (recorded != null) checkFiles(checkNotNull(config.folder), config.attachmentFolder, recorded.values, ::report)
            report("prepare")
            val prepared = if (tag == null) source.prepare(progress)
                else prepareIncremental(config, tag, checkNotNull(recorded), force, progress, ::report) ?: return@withLock
            prepared.use { artifact ->
                if (artifact.content.phraseHash != config.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
                if (!force && artifact.content.empty) return@withLock
                // The provider may have changed while encryption ran. Verify again just before writing.
                withContext(io) { report("verify-active"); requireGrant(checkNotNull(config.folder)); files.verify(config.slots[config.active]) }
                // Saving the first attachment may have recorded the pair's new attachment folder.
                val current = checkNotNull(trusted)
                // Lock before the write begins is cancellation, not an uncertain commit.
                report("record-pending"); persist(current.copy(pending = true))
                withContext(NonCancellable + io) {
                    val inactive = 1 - current.active
                    report("access"); requireGrant(checkNotNull(current.folder))
                    files.writeVerified(current.slots[inactive].uri, artifact, ::report)
                    val slots = current.slots.toMutableList().also { it[inactive] = it[inactive].copy(length = artifact.length, sha256 = artifact.sha256) }
                    report("record-success")
                    persist(current.copy(slots = slots, active = inactive, fingerprint = artifact.content.fingerprint, savedAt = clock(), pending = false, error = null))
                    savedRevision = artifact.content.revision; oversizedRevision = null
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SnapshotChanged) {
            // The edit that removed the attachment also schedules a fresh attempt with current metadata.
            savedRevision = null
        }
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

    /**
     * Confirms from one listing, without reading any, that restore would find every completed file of [recorded] in
     * [directory] (see [BackupService.attachmentEntry]) as the recorded document, at its recorded size. Damage needs the
     * document's own answer to agree; anything less leaves the files unverified.
     */
    private suspend fun checkFiles(grant: String, directory: BackupFolder?, recorded: Collection<AttachmentFile>, report: (String) -> Unit) = withContext(io) {
        val completed = recorded.filter { it.complete }
        if (completed.isEmpty()) return@withContext
        report("check-attachments")
        val folder = directory ?: throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
        requireGrant(grant)
        val listed = files.children(folder.uri).filter { !it.directory }
        for (file in completed) {
            val found = BackupService.attachmentEntry(listed, { it.name }, file.id, file.name) ?: unresolved(file, listed)
            when {
                // Restore would read another document; only a full check can tell whether it is a valid copy.
                found.uri != file.uri -> throw BackupFailure(BackupProblem.IO)
                found.size == file.length -> continue
                found.size == null -> throw BackupFailure(BackupProblem.IO)
            }
            val measured = try { files.measure(file.uri) } catch (_: FileNotFoundException) { throw BackupFailure(BackupProblem.IO) }
            throw BackupFailure(if (measured != null && measured == found.size) BackupProblem.ATTACHMENT_DAMAGED else BackupProblem.IO)
        }
    }
    /**
     * Restore would not find [file]. That is damage only if documents confirm it, not the listing alone: opening the
     * recorded document reports it missing and the listing omits it, or it reports the listed name itself and restore
     * could not reach that name. When the name could resolve and only other entries stand in the way, each of those
     * must also exist under its listed name; a stale duplicate must not pause a healthy pair. Anything else is unverified.
     */
    private fun unresolved(file: AttachmentFile, listed: List<BackupChild>): Nothing {
        val child = listed.firstOrNull { it.uri == file.uri }
        try { files.measure(file.uri) }
        catch (_: FileNotFoundException) { throw BackupFailure(if (child == null) BackupProblem.ATTACHMENT_DAMAGED else BackupProblem.IO) }
        if (child == null || files.displayName(file.uri) != child.name) throw BackupFailure(BackupProblem.IO)
        if (child.name == file.name || child.name.startsWith(file.id)) {
            // The entries restore ranks at or above this one under its rule: exact names first, otherwise ID prefixes.
            val exact = listed.filter { it.name == file.name }
            val rivals = (if (exact.isNotEmpty()) exact else listed.filter { it.name.startsWith(file.id) }) - child
            if (rivals.isEmpty() || !rivals.all(::confirmed)) throw BackupFailure(BackupProblem.IO)
        }
        throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
    }
    /** The document exists and reports the name its listing entry shows. */
    private fun confirmed(entry: BackupChild): Boolean =
        try { files.measure(entry.uri); files.displayName(entry.uri) == entry.name } catch (_: FileNotFoundException) { false }

    /** Captures the content, saves each attachment without a completed file, and prepares the version-4 index. */
    private suspend fun prepareIncremental(config: BackupConfiguration, tag: String, recorded: Map<String, AttachmentFile>, force: Boolean,
                                           progress: (String) -> Unit, report: (String) -> Unit): PreparedBackup? {
        val snapshot = source.capture(progress)
        if (snapshot.content.phraseHash != config.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
        if (!force && snapshot.content.empty) return null
        // Attachments never change; the same ID with other content cannot share this pair's files.
        if (snapshot.attachments.any { a -> recorded[a.id]?.let { it.size != a.size || it.sha256 != a.sha256 } == true }) {
            report("attachment-identity"); throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
        }
        val key = settings.key(tag) ?: throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
        try {
            val saved = recorded.toMutableMap()
            val missing = snapshot.attachments.filter { saved[it.id]?.complete != true }
            val grant = checkNotNull(config.folder)
            var directory = config.attachmentFolder
            missing.forEachIndexed { index, attachment ->
                progress("Saving new attachment ${index + 1} of ${missing.size}")
                val target = directory ?: newAttachmentFolder(grant, checkNotNull(trusted).managedUris, report).also { made ->
                    report("record-folder"); persist(checkNotNull(trusted).copy(attachmentFolder = made))
                    directory = made
                }
                val known = checkNotNull(trusted).managedUris + saved.values.map { it.uri }
                saved[attachment.id] = saveAttachment(tag, grant, target.uri, snapshot, attachment, saved[attachment.id], key, known, report)
            }
            report("prepare-index")
            return source.prepareIndex(snapshot, key, directory?.name, snapshot.attachments.associate { it.id to saved.getValue(it.id).name })
        } finally { key.fill(0) }
    }

    /** Created when a pair saves its first attachment; an index never names a folder the provider did not confirm. */
    private suspend fun newAttachmentFolder(grant: String, known: Set<String>, report: (String) -> Unit): BackupFolder = withContext(io) {
        report("create-folder"); requireGrant(grant)
        val name = "Notes-${timestamp()}-attachments"
        val uri = files.createDirectory(grant, name)
        if (uri.isBlank() || uri in known) throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
        val document = files.document(uri, name)
        if (!document.nameConfirmed) { runCatching { files.delete(uri) }; throw BackupFailure(BackupProblem.IO) }
        BackupFolder(document.uri, document.name)
    }

    /**
     * Writes one attachment file. A recorded but unfinished document is first checked against its expected measurements;
     * otherwise fresh measurements are committed before it is reopened for writing. Completed files are never reopened.
     */
    private suspend fun saveAttachment(tag: String, grant: String, directory: String, snapshot: BackupSnapshot, attachment: BackupAttachment,
                                       unfinished: AttachmentFile?, key: ByteArray, known: Set<String>, report: (String) -> Unit): AttachmentFile {
        check(unfinished?.complete != true)
        suspend fun save(file: AttachmentFile): AttachmentFile { report("record-attachment"); record { begin -> settings.saveFile(tag, file, begin) }; return file }
        suspend fun write(file: AttachmentFile, artifact: PreparedBackup): AttachmentFile {
            save(file)
            withContext(io) { requireGrant(grant); files.writeVerified(file.uri, artifact) { report("attachment-$it") } }
            return save(file.copy(complete = true))
        }
        if (unfinished != null) {
            report("check-unfinished")
            val written = withContext(io) {
                requireGrant(grant)
                try { files.verify(BackupSlot(unfinished.uri, "", unfinished.length, unfinished.digest)); true }
                catch (_: BackupFailure) { false }
                catch (_: FileNotFoundException) { null }
            }
            if (written == true) return save(unfinished.copy(complete = true))
            if (written == false) return source.prepareAttachment(snapshot, attachment, key).use { artifact ->
                write(unfinished.copy(length = artifact.length, digest = artifact.sha256), artifact)
            }
            // The unfinished document is gone; a new document replaces its record.
        }
        return source.prepareAttachment(snapshot, attachment, key).use { artifact ->
            val name = "${attachment.id}.ssna"
            val document = withContext(io) {
                report("create-attachment"); requireGrant(grant)
                val uri = files.create(directory, name)
                if (uri.isBlank() || uri == directory || uri in known) throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
                files.document(uri, name).also { if (!it.nameConfirmed) { runCatching { files.delete(uri) }; throw BackupFailure(BackupProblem.IO) } }
            }
            write(AttachmentFile(attachment.id, attachment.size, attachment.sha256, document.uri, document.name, artifact.length, artifact.sha256), artifact)
        }
    }

    /**
     * Reads both indexes and every attachment they name with restore's checks, discarding the plaintext. Confirmed
     * corruption of the active index or of an attachment pauses writes to the pair; a damaged or interrupted inactive
     * index is only reported. Read and access failures change nothing, and a check that does not finish records nothing.
     */
    suspend fun verify(progress: (String) -> Unit = {}) = mutex.withLock {
        val config = trusted ?: return@withLock
        if (!unlocked() || restartRequired || config.mode != BackupMode.PAIR) return@withLock
        var step = "check"
        fun report(value: String) { step = value; operation = "check-$value"; publish() }
        operation = "check"; checkError = null; publish()
        val indexes = mutableListOf<CheckedIndex>()
        try {
            config.validate()
            requireGrant(checkNotNull(config.folder))
            if (source.inspect().phraseHash != config.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
            val states = MutableList(2) { SlotCheck.VERIFIED }
            for (slot in listOf(config.active, 1 - config.active)) {
                report("index-${"AB"[slot]}")
                val index = checkIndexFile(config.slots[slot])
                when {
                    index != null -> indexes += index
                    slot == config.active -> throw BackupFailure(BackupProblem.ACTIVE_DAMAGED)
                    else -> states[slot] = if (config.pending) SlotCheck.INCOMPLETE else SlotCheck.DAMAGED
                }
            }
            checkAttachmentFiles(config, indexes, progress, ::report)
            report("record")
            persist(checkNotNull(trusted).copy(check = FullCheck(clock(), config.slots.map { it.sha256 }, states)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = error(e, step)
            if (failure.problem in setOf(BackupProblem.ACTIVE_DAMAGED, BackupProblem.ATTACHMENT_DAMAGED) && !restartRequired) {
                transientError = failure
                try { persist(checkNotNull(trusted).copy(error = failure)) }
                catch (_: CancellationException) { /* Pausing is retried by the next check. */ }
                catch (_: Exception) { /* persist set the restart block. */ }
            } else checkError = failure
        } finally {
            indexes.forEach { it.key?.fill(0) }
            operation = null; publish()
        }
    }
    /** Null when the index's content is not the recorded file or fails authentication. */
    private suspend fun checkIndexFile(slot: BackupSlot): CheckedIndex? {
        try { withContext(io) { files.verify(slot) } } catch (_: BackupFailure) { return null }
        return readChecked(slot.uri) { source.checkIndex(it) }
    }
    /** Each attachment named by either index is read once; both must name it identically, under the same key. */
    private suspend fun checkAttachmentFiles(config: BackupConfiguration, indexes: List<CheckedIndex>, progress: (String) -> Unit, report: (String) -> Unit) {
        val named = linkedMapOf<String, Triple<BackupAttachment, String, ByteArray>>()
        for (index in indexes) {
            val names = index.files ?: continue
            val key = checkNotNull(index.key)
            for (attachment in index.attachments) {
                val seen = named.getOrPut(attachment.id) { Triple(attachment, names.getValue(attachment.id), key) }
                if (seen.first != attachment || seen.second != names[attachment.id] || !seen.third.contentEquals(key)) {
                    report("attachments"); throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
                }
            }
        }
        if (named.isEmpty()) return
        report("attachments")
        val directory = config.attachmentFolder ?: throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
        val recorded = settings.files(checkNotNull(config.pairTag))
        val listed = withContext(io) { files.children(directory.uri) }.filter { !it.directory }
        named.values.forEachIndexed { index, (attachment, name, key) ->
            progress("Checking attachment ${index + 1} of ${named.size}")
            val file = recorded[attachment.id]?.takeIf { it.complete && it.name == name && it.size == attachment.size && it.sha256 == attachment.sha256 }
                ?: throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
            // Read what restore would read, which may be a provider's copy rather than the recorded document.
            val found = BackupService.attachmentEntry(listed, { it.name }, attachment.id, name) ?: withContext(io) { unresolved(file, listed) }
            if (readChecked(found.uri) { source.checkAttachment(it, key, attachment); true } == null) throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
        }
    }
    /** Null when the document was read in full but its content failed a check; read and access failures propagate. */
    private suspend fun <T : Any> readChecked(uri: String, check: suspend (InputStream) -> T): T? {
        val input = withContext(io) { Tracked(files.openRead(uri)) }
        return try { input.use { check(it) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (input.failed || e.hasSecurityCause() || e is BackupFailure) throw e else null }
    }

    /**
     * Starts a new pair in [folder], replacing any unfinished setup. Once its pending record is committed, a failure or
     * lock leaves everything for [resumeSetup] or [discardSetup]. Reject delayed picker callbacks when busy. Never adopt a
     * document by name.
     */
    suspend fun choose(folder: String, progress: (String) -> Unit = {}): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return rejectSetup(admission)
        if (!mutex.tryLock()) return rejectSetup(BackupAdmission.BUSY)
        var pairTag: String? = null
        var hadGrant = true
        var started = false
        var step = "prepare"
        operation = "setup"; setupError = null; publish()
        val report: (String) -> Unit = { step = it; operation = "setup-$it"; publish() }
        try {
            if (!unlocked()) return rejectSetup(BackupAdmission.LOCKED)
            if (restartRequired) return rejectSetup(BackupAdmission.RESTART_REQUIRED)
            val old = checkNotNull(trusted)
            val snapshot = source.capture(progress)
            if (old.mode == BackupMode.PAIR && old.phraseHash != snapshot.content.phraseHash && reviewedPhrase != snapshot.content.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
            currentCoroutineContext().ensureActive()
            if (!unlocked()) throw CancellationException()
            old.setup?.let { report("discard-setup"); discardPending(it) }
            withContext(io) {
                hadGrant = files.hasAnyGrant(folder)
                files.retain(folder)
                requireGrant(folder)
            }
            val pending = PendingSetup(folder, newId().also { pairTag = it }, timestamp(), snapshot.content.phraseHash, ownsGrant = !hadGrant)
            val key = Crypto.newStreamingKey()
            try { report("record-key"); record { begin -> settings.saveKey(pending.pairTag, key, begin) } } finally { key.fill(0) }
            report("record-setup-start"); persist(checkNotNull(trusted).copy(setup = pending)) { started = true }
            return completeSetup(snapshot, progress, report)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setupError = error(e, step); return BackupAdmission.FAILED }
        finally {
            if (!started) withContext(NonCancellable + io) {
                pairTag?.let { runCatching { settings.forget(it) } }
                if (!hadGrant && folder != trusted?.grant) runCatching { files.release(folder) }
            }
            operation = null; publish(); mutex.unlock()
        }
    }
    /**
     * Continues an unfinished setup with current content, reusing every attachment file it completed. After a phrase
     * change it starts afresh instead, with a new key and folder, once the new phrase has been reviewed.
     */
    suspend fun resumeSetup(progress: (String) -> Unit = {}): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return rejectSetup(admission)
        if (!mutex.tryLock()) return rejectSetup(BackupAdmission.BUSY)
        var step = "resume"
        operation = "setup"; setupError = null; publish()
        val report: (String) -> Unit = { step = it; operation = "setup-$it"; publish() }
        try {
            val pending = trusted?.setup ?: return BackupAdmission.INELIGIBLE
            val snapshot = source.capture(progress)
            if (snapshot.content.phraseHash != pending.phraseHash) {
                if (reviewedPhrase != snapshot.content.phraseHash) throw BackupFailure(BackupProblem.PHRASE_CHANGED)
                // An index written under the old phrase may already hold this setup's key, so nothing it encrypted is
                // reused. Its files stay as history, like any old pair's; only its device records are forgotten.
                report("restart-setup")
                val fresh = PendingSetup(pending.folder, newId(), timestamp(), snapshot.content.phraseHash, pending.ownsGrant)
                val key = Crypto.newStreamingKey()
                try { record { begin -> settings.saveKey(fresh.pairTag, key, begin) } } finally { key.fill(0) }
                persist(checkNotNull(trusted).copy(setup = fresh))
                withContext(NonCancellable + io) { runCatching { settings.forget(pending.pairTag) } }
            }
            return completeSetup(snapshot, progress, report)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setupError = error(e, step); return BackupAdmission.FAILED }
        finally { operation = null; publish(); mutex.unlock() }
    }
    /** Removes only the documents an unfinished setup created and its records; the active pair is untouched. */
    suspend fun discardSetup(): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return admission
        if (!mutex.tryLock()) return BackupAdmission.BUSY
        operation = "setup-discard"; publish()
        try {
            trusted?.setup?.let { discardPending(it) }
            setupError = null
            return BackupAdmission.ACCEPTED
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { setupError = error(e, "discard-setup"); return BackupAdmission.FAILED }
        finally { operation = null; publish(); mutex.unlock() }
    }
    private suspend fun discardPending(pending: PendingSetup) {
        val recorded = settings.files(pending.pairTag).values.map { it.uri }
        // Committed first: once no record names these documents, removing them can be interrupted safely.
        persist(checkNotNull(trusted).copy(setup = null))
        withContext(NonCancellable + io) {
            val active = checkNotNull(trusted)
            // Attachment files before their folder, which some providers delete only when empty.
            (recorded + pending.created.asReversed()).filter { it !in active.managedUris }.forEach { runCatching { files.delete(it) } }
            runCatching { settings.forget(pending.pairTag) }
            if (pending.ownsGrant && pending.folder != active.grant) runCatching { files.release(pending.folder) }
        }
    }
    /**
     * Saves every attachment without a completed file, then writes and verifies both indexes and activates the pair.
     * Each directory and index document is recorded before its name is queried; the previous pair keeps its files and
     * configuration until activation commits.
     */
    private suspend fun completeSetup(snapshot: BackupSnapshot, progress: (String) -> Unit, report: (String) -> Unit): BackupAdmission {
        var pending = checkNotNull(checkNotNull(trusted).setup)
        suspend fun update(next: PendingSetup) { persist(checkNotNull(trusted).copy(setup = next)); pending = next }
        suspend fun created(uri: String, next: (PendingSetup) -> PendingSetup = { it }) {
            if (uri.isBlank() || uri in checkNotNull(trusted).managedUris) throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
            update(next(pending.copy(created = pending.created + uri)))
        }
        val tag = pending.pairTag
        requireGrant(pending.folder)
        val saved = settings.files(tag).toMutableMap()
        if (snapshot.attachments.any { a -> saved[a.id]?.let { it.size != a.size || it.sha256 != a.sha256 } == true }) {
            report("attachment-identity"); throw BackupFailure(BackupProblem.ATTACHMENT_DAMAGED)
        }
        val key = settings.key(tag) ?: throw BackupFailure(BackupProblem.INVALID_CONFIGURATION)
        try {
            val missing = snapshot.attachments.filter { saved[it.id]?.complete != true }
            missing.forEachIndexed { index, attachment ->
                progress("Saving attachment ${index + 1} of ${missing.size}")
                // kotlin.run: this class's own run() is the save.
                val directory = pending.attachmentFolder ?: kotlin.run {
                    report("create-folder"); requireGrant(pending.folder)
                    val name = "Notes-${pending.timestamp}-attachments"
                    val uri = withContext(io) { files.createDirectory(pending.folder, name) }
                    created(uri)
                    val document = withContext(io) { files.document(uri, name) }
                    // An index never names a folder the provider did not confirm; Discard removes this one.
                    if (!document.nameConfirmed) throw BackupFailure(BackupProblem.IO)
                    BackupFolder(document.uri, document.name).also { update(pending.copy(attachmentFolder = it)) }
                }
                val known = checkNotNull(trusted).managedUris + saved.values.map { it.uri }
                saved[attachment.id] = saveAttachment(tag, pending.folder, directory.uri, snapshot, attachment, saved[attachment.id], key, known, report)
            }
            // Files completed by an earlier attempt may have gone since: check every file the indexes will name, as a
            // routine save does, before either index is written. Failure leaves the previous pair active.
            checkFiles(pending.folder, pending.attachmentFolder, snapshot.attachments.map { saved.getValue(it.id) }, report)
            report("prepare-index")
            return source.prepareIndex(snapshot, key, pending.attachmentFolder?.name, snapshot.attachments.associate { it.id to saved.getValue(it.id).name }).use { artifact ->
                val slots = listOf("A", "B").mapIndexed { index, label ->
                    currentCoroutineContext().ensureActive()
                    val document = pending.slots.getOrNull(index) ?: kotlin.run {
                        report("create-$label"); requireGrant(pending.folder)
                        val requested = "Notes-${pending.timestamp}-$label.ssnb"
                        val uri = withContext(io) { files.create(pending.folder, requested) }
                        created(uri)
                        val named = withContext(io) { files.document(uri, requested) }
                        SetupDocument(named.uri, named.name, named.nameConfirmed).also { update(pending.copy(slots = pending.slots + it)) }
                    }
                    withContext(io) { requireGrant(pending.folder); files.writeVerified(document.uri, artifact, report) }
                    BackupSlot(document.uri, document.name, artifact.length, artifact.sha256, document.nameConfirmed)
                }
                val old = checkNotNull(trusted)
                val next = BackupConfiguration(mode = BackupMode.PAIR, folder = pending.folder, slots = slots, active = 1,
                    phraseHash = artifact.content.phraseHash, fingerprint = artifact.content.fingerprint, savedAt = clock(),
                    pairTag = tag, attachmentFolder = pending.attachmentFolder)
                next.validate(); report("record-setup")
                persist(next)
                savedRevision = artifact.content.revision; oversizedRevision = null; reviewedPhrase = null; grantAvailable = true
                withContext(NonCancellable + io) {
                    old.grant?.takeIf { it != pending.folder }?.let { runCatching { files.release(it) } }
                    old.pairTag?.let { runCatching { settings.forget(it) } }
                }
                BackupAdmission.ACCEPTED
            }
        } finally { key.fill(0) }
    }
    /** Leaves every file in place, including those of an unfinished setup. */
    suspend fun turnOff(): BackupAdmission {
        val admission = admission()
        if (!admission.accepted) return admission
        if (!mutex.tryLock()) return BackupAdmission.BUSY
        try {
            val old = checkNotNull(trusted)
            persist(BackupConfiguration())
            withContext(NonCancellable + io) {
                old.grant?.let { runCatching { files.release(it) } }
                old.pairTag?.let { runCatching { settings.forget(it) } }
                old.setup?.let { setup ->
                    runCatching { settings.forget(setup.pairTag) }
                    if (setup.ownsGrant && setup.folder != old.grant) runCatching { files.release(setup.folder) }
                }
            }
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

/** Remembers whether reading the document itself failed, which tells an unreadable file apart from damaged content. */
private class Tracked(input: InputStream) : FilterInputStream(input) {
    var failed = false
        private set
    override fun read(): Int = try { `in`.read() } catch (e: Exception) { failed = true; throw e }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = try { `in`.read(bytes, offset, length) } catch (e: Exception) { failed = true; throw e }
    override fun skip(count: Long): Long = try { `in`.skip(count) } catch (e: Exception) { failed = true; throw e }
}
