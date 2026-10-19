package dev.securenotes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.securenotes.NotesApplication
import dev.securenotes.R
import dev.securenotes.backup.BackupAdmission
import dev.securenotes.backup.BackupSetupToken
import dev.securenotes.document.*
import dev.securenotes.search.*
import dev.securenotes.security.Passphrase
import dev.securenotes.storage.AttachmentRow
import dev.securenotes.storage.displayName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LIST, NOTE, SEARCH, SETTINGS, IMAGE }
data class SharedContent(val text: String?, val uris: List<Uri>, val mime: String?)
data class EditorSelection(val field: HitKind, val start: Int, val end: Int = start)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as NotesApplication
    val repository = app.repository
    val backups = app.backups
    val wordlist: Set<String> by lazy { Passphrase.wordlist(app).toSet() }
    /** The device key is gone but the vault remains; only the recovery passphrase can open it. */
    var keyUnavailable by mutableStateOf(false)
    /** First run on a new phone: open the restore picker as soon as the new empty store is unlocked. */
    var restoreAfterUnlock by mutableStateOf(false)
    /** Words shown during first-run setup until the user proves they saved them. */
    var setupWords by mutableStateOf<List<String>?>(null)
    /** Words shown after a fresh authentication from Settings. */
    var revealedWords by mutableStateOf<List<String>?>(null)
    /** A "Show recovery passphrase" prompt is running. Kept here, not in the activity, so it survives rotation. */
    var revealing = false
    /** Non-local providers need an explicit acknowledgement after the folder picker. */
    var backupProviderWarning by mutableStateOf<Uri?>(null)
    private var backupSetupToken: BackupSetupToken? = null
    // Retained even across lock until that launch's callback arrives; it must not adopt a newer token.
    private var pendingBackupPicker: BackupSetupToken? = null
    var reviewingBackup by mutableStateOf(false)
        private set
    var screen by mutableStateOf(Screen.LIST)
    var note by mutableStateOf<Note?>(null)
    var editing by mutableStateOf(false)
    var newDraft by mutableStateOf(false)
    var focusTitle by mutableStateOf(false)
    /** A pending request to focus the body editor at this offset. */
    var bodyFocus by mutableStateOf<Int?>(null)
    var attachments by mutableStateOf<Map<String, AttachmentRow>>(emptyMap())
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchHit>>(emptyList())
    var hit by mutableStateOf<SearchHit?>(null)
    var imageId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var restoreUri by mutableStateOf<Uri?>(null)
    var restoreCount by mutableStateOf<Int?>(null)
    /** The recorded attachment folder name, shown while an authenticated version-4 backup waits for that folder. */
    var restoreFolder by mutableStateOf<String?>(null)
    private var restoreFolderPicked: String? = null
    var passwordMode by mutableStateOf<String?>(null)
    var share by mutableStateOf<SharedContent?>(null)
    var choosingDestination by mutableStateOf(false)
    var cameraRecovery by mutableStateOf(false)
    /** The attachment the save picker was opened for; locking cancels the save. */
    private var pendingSave: String? = null
    var listAnchor: String? = null
    var listOffset: Int = 0
    var historyRevision by mutableIntStateOf(0)
    var filter by mutableStateOf(NoteFilter())
        private set
    val tagCatalog = combine(repository.tags, app.unlocked) { values, unlocked -> if (unlocked) values else emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // Synchronous reads also see a just-created tag before the flow collector resumes.
    val tags: List<Tag> get() = if (app.unlocked.value) repository.tags.value else emptyList()
    val filterLabel: String get() = filterLabel(tags)
    fun filterLabel(catalog: List<Tag>) = if (filter.untagged) "Untagged" else catalog.firstOrNull { it.id == filter.tagId }?.name ?: "Notes"
    var findOpen by mutableStateOf(false)
        private set
    var find by mutableStateOf(FindState())
        private set
    var findScrollRevision by mutableIntStateOf(0)
        private set
    var findCloseRevision by mutableIntStateOf(0)
        private set
    var findReturn: EditorSelection? = null
        private set
    var findScrollTarget: FindMatch? = null
        private set
    private var editorSelection = EditorSelection(HitKind.TITLE, 0)
    private var editorRevision = 0L
    private var findActionRevision = 0L
    private var returnRevision = 0L
    private val finder = FindSession(viewModelScope, publish = { find = it }, scroll = {
        findScrollTarget = find.active; findScrollRevision++
    })
    val history = EditHistory()
    private var searchJob: Job? = null
    private var operation: Job? = null
    private var activeOperation: Job? = null
    private val operations = mutableSetOf<Job>()
    private val operationMutex = Mutex()
    // viewModelScope runs on Main.immediate, so these collectors start inside the constructor. Any property they
    // touch must be declared above this block; properties below it are not initialized yet.
    init {
        viewModelScope.launch { tagCatalog.collect { if (app.unlocked.value) reconcileTags() } }
        viewModelScope.launch { repository.issue.collect { issue -> if (issue != null) { error = issue; repository.issue.value = null } } }
        viewModelScope.launch { repository.notes.collect {
            if (app.unlocked.value) runCatching { reloadAttachments() }
        } }
        viewModelScope.launch { app.unlocked.collect { unlocked ->
            if (!unlocked) {
                operations.toList().forEach { it.cancel() }; searchJob?.cancel(); backups.discard()
                note = null; attachments = emptyMap(); results = emptyList(); query = ""; hit = null
                clearFind(); filter = NoteFilter(); listAnchor = null; listOffset = 0
                history.clear(); historyRevision++; screen = Screen.LIST; editing = false
                busy = null; passwordMode = null; restoreCount = null; restoreFolder = null; restoreFolderPicked = null; setupWords = null; revealedWords = null; backupProviderWarning = null; restoreUri = null
                backupSetupToken = null; reviewingBackup = false; newDraft = false; imageId = null; focusTitle = false; bodyFocus = null
                pendingSave = null
            } else { keyUnavailable = false; reconcileTags(); reloadAttachments() }
        } }
        viewModelScope.launch { repository.passphraseConfirmed.collect { confirmed ->
            setupWords = null
            if (confirmed == false) runCatching { setupWords = repository.passphrase().split(' ') }
        } }
    }
    /** Returns null when the action is refused; the returned job may also finish without running it, after a lock. */
    fun task(message: String?, failure: String, action: suspend () -> Unit): Job? {
        if (reviewingBackup) return null
        val job = app.scope.launch { operationMutex.withLock {
            if (!app.unlocked.value) return@withLock
            activeOperation = currentCoroutineContext()[Job]
            val indicator = launch { delay(300); busy = message }
            try { action() } catch (_: CancellationException) { /* Paused/cancelled, existing data retained. */ }
            catch (e: Exception) { error = if (e is dev.securenotes.backup.BackupPasswordException) "Incorrect backup password or damaged backup file." else failure }
            finally { indicator.cancel(); busy = null; activeOperation = null }
        } }
        operation = job; operations.add(job); job.invokeOnCompletion { operations.remove(job) }
        return job
    }
    fun progress(message: String) { app.scope.launch { if (operation?.isActive == true && app.unlocked.value) busy = message } }
    fun cancelOperation() { activeOperation?.cancel(); busy = null }
    suspend fun reloadAttachments() { attachments = repository.attachments() }
    fun create() = createDraft(inheritTag = true)
    private fun createDraft(inheritTag: Boolean) {
        if (reviewingBackup) return
        clearFind()
        rememberEditorSelection(HitKind.TITLE, 0)
        note = Note(tagIds = if (inheritTag) listOfNotNull(filter.tagId) else emptyList()); newDraft = true; editing = true; focusTitle = true; bodyFocus = null
        history.clear(); historyRevision++; hit = null; screen = Screen.NOTE
    }
    fun open(value: Note, match: SearchHit? = null) {
        if (reviewingBackup) return
        clearFind()
        rememberEditorSelection(HitKind.TITLE, 0)
        note = value; newDraft = false; editing = false; focusTitle = false; bodyFocus = null
        history.clear(); historyRevision++; hit = match; screen = Screen.NOTE
    }
    /** Typing changes share one undo step until the user pauses; other changes are separate steps. */
    fun change(value: Note, record: Boolean = true, typing: Boolean = false) {
        if (reviewingBackup) return
        val old = note ?: return
        if (old == value) return
        if (record && editing) history.record(old, typing)
        historyRevision++
        val updated = value.copy(updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1), tagIds = value.tagIds.filter { id -> tags.any { it.id == id } }.distinct())
        try {
            repository.enqueue(updated, !newDraft); note = updated
            if (old.title != updated.title || old.document.text != updated.document.text) {
                if (findOpen) computeFind(jump = false)
            }
        }
        catch (_: CancellationException) { app.lock() }
    }
    fun editBody(document: Document, typing: Boolean) { note?.let { change(it.copy(document = document), typing = typing) } }
    /** Edits applied without a field callback, including history replay. Metadata alone does not move the cursor. */
    private fun changeEditorContent(value: Note, record: Boolean = true) {
        val old = note ?: return
        change(value, record)
        val current = note ?: return
        if (old.title != current.title || old.document.text != current.document.text ||
            old.document.lines != current.document.lines || old.document.bold != current.document.bold) {
            editorInteracted(editorSelection.field, editorSelection.start, editorSelection.end)
        }
    }
    fun setChecked(line: Int, checked: Boolean) { note?.let { changeEditorContent(it.copy(document = DocumentEdits.setChecked(it.document, line, checked))) } }
    fun moveAttachment(id: String, delta: Int) {
        val n = note ?: return
        val list = n.document.attachments
        val index = list.indexOf(id); val target = index + delta
        if (index < 0 || target !in list.indices) return
        change(n.copy(document = n.document.copy(attachments = list.toMutableList().apply { add(target, removeAt(index)) })))
    }
    fun removeAttachment(id: String) { note?.let { n -> change(n.copy(document = n.document.copy(attachments = n.document.attachments - id))) } }
    fun reorderNotes(ids: List<String>, finished: () -> Unit = {}) {
        task(null, "The note order could not be saved.") {
            try { repository.reorderVisible(ids) } finally { finished() }
        }
    }
    fun undo() { if (reviewingBackup) return; note?.let { n -> history.undo(n)?.let { changeEditorContent(it, false) } } }
    fun redo() { if (reviewingBackup) return; note?.let { n -> history.redo(n)?.let { changeEditorContent(it, false) } } }
    /** Enters editing with the cursor in the title, or in the body at [bodyOffset]. */
    fun enterEditing(bodyOffset: Int? = null) {
        if (reviewingBackup) return
        editorInteracted(if (bodyOffset == null) HitKind.TITLE else HitKind.BODY, bodyOffset ?: 0)
        editing = true; focusTitle = bodyOffset == null; bodyFocus = bodyOffset
    }
    suspend fun flush() = repository.flush()
    fun back() {
        if (reviewingBackup) return
        if (screen == Screen.NOTE && findOpen) { closeFind(); return }
        if (screen == Screen.IMAGE) { imageId = null; screen = Screen.NOTE; return }
        if (screen != Screen.NOTE) { screen = Screen.LIST; query = ""; results = emptyList(); choosingDestination = false; return }
        leaveNote()
    }
    /** Done finishes editing in one action even when Find is open. Failed saves retain editing. */
    fun finishEditing() { if (reviewingBackup) return; clearFind(); focusTitle = false; bodyFocus = null; leaveNote() }
    /** Settings navigation completes the entire session, including an image's underlying note. */
    fun reviewBackup() {
        if (reviewingBackup || !app.unlocked.value) return
        reviewingBackup = true
        val job = app.scope.launch(start = CoroutineStart.LAZY) {
            operationMutex.withLock {
                if (!app.unlocked.value) return@withLock
                try {
                    if (note != null) {
                        flush()
                        if (newDraft && note?.isEmpty == true) note?.let { repository.delete(it.id) }
                        endSession()
                    }
                    clearFind(); searchJob?.cancel(); query = ""; results = emptyList(); hit = null
                    note = null; editing = false; newDraft = false; imageId = null
                    focusTitle = false; bodyFocus = null; choosingDestination = false
                    rememberEditorSelection(HitKind.TITLE, 0)
                    screen = Screen.SETTINGS
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = app.getString(R.string.backup_navigation_save_failed) }
            }
        }
        operations += job
        job.invokeOnCompletion { operations.remove(job); reviewingBackup = false }
        job.start()
    }
    private fun leaveNote() {
        task(null, "Changes could not be saved. Please free some storage before leaving this note.") {
            flush()
            val discard = newDraft && note?.isEmpty == true
            if (discard) note?.let { repository.delete(it.id) }
            endSession()
            if (editing && !discard) { editing = false; focusTitle = false; bodyFocus = null }
            else { clearFind(); screen = Screen.LIST; note = null }
        }
    }
    private suspend fun endSession() {
        history.clear(); historyRevision++
        repository.collectGarbage()
    }
    fun delete() {
        val id = note?.id ?: return
        task("Deleting note…", "The note could not be deleted.") {
            flush()
            repository.delete(id); clearFind(); history.clear(); note = null; screen = Screen.LIST; reloadAttachments()
        }
    }
    fun search(value: String) {
        query = value; searchJob?.cancel()
        val selected = if (choosingDestination) NoteFilter() else filter
        searchJob = viewModelScope.launch {
            delay(150)
            try { results = repository.search(value, selected) } catch (_: CancellationException) { } catch (_: Exception) { error = "Search could not be completed." }
        }
    }
    fun import(uris: List<Uri>, mime: String? = null) {
        val initial = note ?: return
        task("Importing…", "The file could not be imported. Check the source and available storage.") {
            flush()
            var updated = initial
            for (uri in uris) {
                // The repository releases the grant after commit; an interrupted job still needs it.
                updated = repository.import(updated, uri, mime); note = updated
            }
            reloadAttachments(); app.startIndexing()
        }
    }
    /** Opens the save picker with the attachment's name and type; [launch] takes that name and type. */
    fun requestSave(id: String, launch: (Pair<String, String>) -> Unit) {
        val attachment = attachments[id] ?: return
        pendingSave = id
        try { launch(attachment.displayName to attachment.mime) }
        catch (_: Exception) { pendingSave = null; error = "No installed application can save files." }
    }
    fun saveAttachment(destination: Uri?) {
        val id = pendingSave
        pendingSave = null
        if (destination == null) return
        val discard = { app.scope.launch(Dispatchers.IO) { repository.discardUnused(destination) } }
        if (id == null || !app.unlocked.value) { discard(); return }
        var started = false
        val job = task("Saving…", "The attachment could not be saved. Check the destination and available storage.") {
            started = true
            repository.saveCopy(id, destination)
            android.widget.Toast.makeText(app, "Saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        // Once started, saveCopy cleans up itself; a save cancelled or locked out while queued never reaches it.
        if (job == null) discard() else job.invokeOnCompletion { if (!started) discard() }
    }
    fun receive(content: SharedContent) { share = content }
    fun prepareCamera(launch: (Uri) -> Unit) {
        val destination = note ?: return
        task("Preparing camera…", "The camera could not be opened.") {
            val uri = app.camera.prepare(destination)
            try { launch(uri) } catch (e: Exception) { app.camera.result(false); app.camera.finish(); throw e }
        }
    }
    suspend fun recoverCamera() {
        operation?.join()
        if (!app.unlocked.value) return
        // Every unlock runs this check; only a photo returned by the camera is an import worth announcing.
        val message = if (app.camera.hasResult()) "Importing photo…" else null
        task(message, "The photo could not be imported. Unlock again to retry, or discard the unfinished capture.") {
            app.camera.cleanOrphans()
            cameraRecovery = app.camera.hasPending()
            if (app.camera.hasResult()) {
                val imported = app.camera.finish()
                if (imported != null) { open(imported); reloadAttachments(); app.startIndexing() }
                cameraRecovery = false
            }
        }
    }
    fun resolveCamera(keep: Boolean) { cameraRecovery = false; app.camera.result(keep) }
    fun acceptShare(destination: Note?) {
        val incoming = share ?: return
        share = null; choosingDestination = false
        if (destination == null) createDraft(inheritTag = false) else { open(destination); editing = true }
        incoming.text?.takeIf { it.isNotBlank() }?.let { text ->
            val n = note!!
            changeEditorContent(n.copy(document = DocumentEdits.appendText(n.document, text)))
        }
        if (incoming.uris.isNotEmpty()) import(incoming.uris, incoming.mime)
    }
    /** The passphrase that encrypted the selected backup; it becomes this store's recovery passphrase. */
    fun submitPassword(password: CharArray) {
        passwordMode = null
        task("Validating backup…", "The backup could not be restored. Check the passphrase, file integrity, version, and available storage.") {
            try { stageRestore(checkNotNull(restoreUri), password) }
            catch (_: dev.securenotes.backup.BackupPasswordException) { passwordMode = "restore"; error = "Incorrect recovery phrase or damaged backup. You can try again." }
            finally { password.fill('\u0000') }
        }
        operation?.invokeOnCompletion { password.fill('\u0000') }
    }
    fun selectRestore(uri: Uri?) {
        restoreAfterUnlock = false
        if (uri == null) return
        task("Checking backup…", "This file is not a supported Notes backup, is incomplete, or could not be read.") {
            backups.checkHeader(uri); restoreUri = uri
            val words = if (repository.passphraseConfirmed.value == true) repository.access { it.passphrase() }?.toCharArray() else null
            if (words == null) passwordMode = "restore"
            else try { stageRestore(uri, words) }
            catch (_: dev.securenotes.backup.BackupPasswordException) { passwordMode = "restore" }
            finally { words.fill('\u0000') }
        }
    }
    private suspend fun stageRestore(uri: Uri, words: CharArray) {
        try { restoreCount = backups.stage(uri, words, ::progress) }
        catch (e: dev.securenotes.backup.AttachmentFolderNeeded) { restoreFolder = e.folder ?: "" }
    }
    /** Hides the folder prompt while the picker is open; the authenticated index waits in [backups] until locking. */
    fun chooseRestoreFolder(launch: () -> Unit) {
        val folder = restoreFolder ?: return
        restoreFolderPicked = folder; restoreFolder = null
        try { launch() } catch (_: Exception) { restoreFolderPicked = null; restoreFolder = folder; error = "The folder picker could not be opened. Try again." }
    }
    fun selectRestoreFolder(uri: Uri?) {
        val folder = restoreFolderPicked ?: return
        restoreFolderPicked = null
        if (uri == null) { cancelRestore(); return }
        task("Validating attachments…", "The attachments could not be restored. Choose the folder that holds this backup's attachments and check that every file has finished syncing. Your current notes were not changed.") {
            try { restoreCount = backups.stageFolder(dev.securenotes.backup.DocumentTreeFolder(app.contentResolver, uri), ::progress) }
            catch (e: Exception) { if (e !is CancellationException && backups.awaitingFolder) restoreFolder = folder; throw e }
        }
    }
    fun confirmPassphrase() { task(null, "The confirmation could not be saved.") { repository.confirmPassphrase() } }
    /** Called by the activity only after a fresh vault-key authentication. */
    fun revealPassphrase() { task(null, "The recovery passphrase could not be read.") { revealedWords = repository.passphrase().split(' ') } }
    fun finishPhraseReview() {
        revealedWords?.let { app.autoBackup.phraseReviewed(dev.securenotes.backup.BackupService.phraseHash(it.joinToString(" "))) }
        revealedWords = null
    }
    fun canChooseBackup(): Boolean {
        val admission = app.backupScheduler.admission()
        if (activeOperation != null || !admission.accepted) {
            app.autoBackup.rejectSetup(if (admission.accepted) BackupAdmission.BUSY else admission)
            return false
        }
        return true
    }
    /** Called immediately before launch. Re-selection keeps the same reservation through the warning. */
    fun launchBackupPicker(launch: () -> Unit) {
        if (pendingBackupPicker != null) { app.autoBackup.rejectSetup(BackupAdmission.BUSY); return }
        if (backupSetupToken != null && !app.backupScheduler.isCurrent(backupSetupToken)) backupSetupToken = null
        if (backupSetupToken == null) {
            if (activeOperation != null) { app.autoBackup.rejectSetup(BackupAdmission.BUSY); return }
            val result = app.backupScheduler.beginSetup()
            if (!result.admission.accepted) return
            backupSetupToken = result.token
        }
        backupProviderWarning = null
        pendingBackupPicker = backupSetupToken
        try { launch() }
        catch (_: Exception) { pendingBackupPicker = null; cancelBackupSelection(); error = app.getString(R.string.backup_picker_failed) }
    }
    fun cancelBackupSelection() {
        app.backupScheduler.cancelSetup(backupSetupToken)
        backupSetupToken = null; backupProviderWarning = null
    }
    fun chooseBackup(uri: Uri?) {
        val token = pendingBackupPicker
        pendingBackupPicker = null
        if (uri == null) {
            app.backupScheduler.cancelSetup(token)
            if (backupSetupToken === token) { backupSetupToken = null; backupProviderWarning = null }
            return
        }
        if (!app.backupScheduler.isCurrent(token)) {
            app.autoBackup.rejectSetup(BackupAdmission.SELECT_AGAIN); return
        }
        if (uri.authority !in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")) backupProviderWarning = uri
        else submitBackupSetup(token, uri)
    }
    fun acceptBackupProvider() {
        val uri = backupProviderWarning ?: return
        backupProviderWarning = null
        submitBackupSetup(backupSetupToken, uri)
    }
    private fun submitBackupSetup(token: BackupSetupToken?, uri: Uri) {
        val result = app.backupScheduler.submitSetup(token, uri.toString())
        // Accepted (including queued) work belongs to the process-wide scheduler, not this Activity.
        if (result.admission.accepted && backupSetupToken === token) backupSetupToken = null
    }
    fun checkBackupAccess() { if (canChooseBackup()) app.backupScheduler.refreshAccess() }
    fun backupNow() { if (canChooseBackup()) app.backupScheduler.manual() }
    fun turnOffBackup() { if (canChooseBackup()) app.backupScheduler.disable() }
    fun restore() {
        restoreCount = null
        task("Replacing notes…", "Restore could not be completed. Existing data has been retained where possible.") {
            backups.commit(); clearFind(); note = null; filter = NoteFilter(); reloadAttachments(); screen = Screen.LIST; app.startIndexing()
        }
    }
    fun cancelRestore() { backups.discard(); restoreCount = null; restoreFolder = null; restoreFolderPicked = null }
    fun selectFilter(value: NoteFilter) {
        filter = value; listAnchor = null; listOffset = 0
        if (screen == Screen.SEARCH) search(query)
    }
    fun assignTag(id: String, assigned: Boolean) {
        note?.let { change(it.copy(tagIds = if (assigned) (it.tagIds + id).distinct() else it.tagIds - id)) }
    }
    private fun reconcileTags() {
        if (!app.unlocked.value) return
        val ids = tags.map { it.id }.toSet()
        if (filter.tagId != null && filter.tagId !in ids) selectFilter(NoteFilter())
        note = note?.let { it.copy(tagIds = it.tagIds.filter(ids::contains)) }
        if (history.hasTagsOutside(ids)) { history.clear(); historyRevision++ }
    }
    suspend fun createTag(name: String): Tag = repository.createTag(name)
    suspend fun renameTag(id: String, name: String) { repository.renameTag(id, name) }
    suspend fun deleteTag(id: String) { repository.deleteTag(id); reconcileTags() }

    fun rememberEditorSelection(field: HitKind, start: Int, end: Int = start) {
        editorSelection = EditorSelection(field, start.coerceAtLeast(0), end.coerceAtLeast(0))
    }
    fun editorInteracted(field: HitKind, start: Int, end: Int = start) {
        rememberEditorSelection(field, start, end); editorRevision++
        finder.cancelScroll(); findScrollTarget = null; findScrollRevision++; findReturn = null
    }
    fun openFind() {
        if (reviewingBackup) return
        clearFind(); findActionRevision = editorRevision
        findOpen = true; focusTitle = false; bodyFocus = null
    }
    fun findQuery(value: String) {
        findActionRevision = editorRevision; findScrollTarget = null; findScrollRevision++
        computeFind(jump = true, query = value)
    }
    private fun computeFind(jump: Boolean, query: String = find.query) {
        val snapshot = note ?: return
        finder.search(snapshot.title, snapshot.document.text, query, jump)
    }
    fun moveFind(delta: Int) {
        if (find.pending || find.matches.isEmpty()) return
        findActionRevision = editorRevision; finder.move(delta)
    }
    fun closeFind() {
        if (!findOpen) return
        val id = note?.id; val revision = editorRevision
        val useResult = editing && findActionRevision == revision
        val fallback = editorSelection
        findOpen = false; findScrollTarget = null; findScrollRevision++
        finder.close(resolve = useResult) { result ->
            if (app.unlocked.value && note?.id == id && editorRevision == revision && !findOpen) {
                findReturn = if (editing) result.active?.takeIf { useResult }?.let { EditorSelection(it.kind, it.start) } ?: fallback else null
                returnRevision = revision; findCloseRevision++
            }
        }
    }
    fun consumeFindReturn(): EditorSelection? {
        val result = findReturn.takeIf { returnRevision == editorRevision && !findOpen && editing && app.unlocked.value }
        findReturn = null
        return result
    }
    private fun clearFind() {
        finder.clear(); findOpen = false; findReturn = null
        findScrollTarget = null; findScrollRevision++
    }
    override fun onCleared() { cancelBackupSelection(); backups.discard() }
}
