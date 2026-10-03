package dev.securenotes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.securenotes.NotesApplication
import dev.securenotes.document.*
import dev.securenotes.search.SearchHit
import dev.securenotes.security.Passphrase
import dev.securenotes.storage.AttachmentRow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LIST, NOTE, SEARCH, SETTINGS, IMAGE }
data class SharedContent(val text: String?, val uris: List<Uri>, val mime: String?)

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
    /** A chosen destination that already holds a backup (perhaps the only copy from another phone): overwriting it needs confirmation. */
    var replaceBackup by mutableStateOf<Uri?>(null)
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
    var passwordMode by mutableStateOf<String?>(null)
    var share by mutableStateOf<SharedContent?>(null)
    var choosingDestination by mutableStateOf(false)
    var cameraRecovery by mutableStateOf(false)
    var listAnchor: String? = null
    var listOffset: Int = 0
    var historyRevision by mutableIntStateOf(0)
    val history = EditHistory()
    private var searchJob: Job? = null
    private var operation: Job? = null
    private var activeOperation: Job? = null
    private val operations = mutableSetOf<Job>()
    private val operationMutex = Mutex()
    // viewModelScope runs on Main.immediate, so these collectors start inside the constructor. Any property they
    // touch must be declared above this block; properties below it are not initialized yet.
    init {
        viewModelScope.launch { repository.issue.collect { issue -> if (issue != null) { error = issue; repository.issue.value = null } } }
        viewModelScope.launch { repository.notes.collect {
            if (app.unlocked.value) runCatching { reloadAttachments() }
        } }
        viewModelScope.launch { app.unlocked.collect { unlocked ->
            if (!unlocked) {
                operations.toList().forEach { it.cancel() }; searchJob?.cancel(); backups.discard()
                note = null; attachments = emptyMap(); results = emptyList(); query = ""; hit = null
                history.clear(); historyRevision++; screen = Screen.LIST; editing = false
                busy = null; passwordMode = null; restoreCount = null; setupWords = null; revealedWords = null; replaceBackup = null
            } else { keyUnavailable = false; reloadAttachments() }
        } }
        viewModelScope.launch { repository.passphraseConfirmed.collect { confirmed ->
            setupWords = null
            if (confirmed == false) runCatching { setupWords = repository.passphrase().split(' ') }
        } }
    }
    fun task(message: String?, failure: String, action: suspend () -> Unit) {
        val job = app.scope.launch { operationMutex.withLock {
            if (!app.unlocked.value) return@withLock
            activeOperation = currentCoroutineContext()[Job]
            val indicator = launch { delay(300); busy = message }
            try { action() } catch (_: CancellationException) { /* Paused/cancelled, existing data retained. */ }
            catch (e: Exception) { error = if (e is dev.securenotes.backup.BackupPasswordException) "Incorrect backup password or damaged backup file." else failure }
            finally { indicator.cancel(); busy = null; activeOperation = null }
        } }
        operation = job; operations.add(job); job.invokeOnCompletion { operations.remove(job) }
    }
    fun progress(message: String) { app.scope.launch { if (operation?.isActive == true && app.unlocked.value) busy = message } }
    fun cancelOperation() { activeOperation?.cancel(); busy = null }
    suspend fun reloadAttachments() { attachments = repository.attachments() }
    fun create() {
        note = Note(); newDraft = true; editing = true; focusTitle = true; bodyFocus = null
        history.clear(); historyRevision++; hit = null; screen = Screen.NOTE
    }
    fun open(value: Note, match: SearchHit? = null) {
        note = value; newDraft = false; editing = false; focusTitle = false; bodyFocus = null
        history.clear(); historyRevision++; hit = match; screen = Screen.NOTE
    }
    /** Typing changes share one undo step until the user pauses; other changes are separate steps. */
    fun change(value: Note, record: Boolean = true, typing: Boolean = false) {
        val old = note ?: return
        if (old == value) return
        if (record && editing) history.record(old, typing)
        historyRevision++
        val updated = value.copy(updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1))
        try { repository.enqueue(updated, !newDraft); note = updated }
        catch (_: CancellationException) { app.lock() }
    }
    fun editBody(document: Document, typing: Boolean) { note?.let { change(it.copy(document = document), typing = typing) } }
    fun setChecked(line: Int, checked: Boolean) { note?.let { change(it.copy(document = DocumentEdits.setChecked(it.document, line, checked))) } }
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
            try { repository.reorder(ids) } finally { finished() }
        }
    }
    fun undo() { note?.let { n -> history.undo(n)?.let { change(it, false) } } }
    fun redo() { note?.let { n -> history.redo(n)?.let { change(it, false) } } }
    /** Enters editing with the cursor in the title, or in the body at [bodyOffset]. */
    fun enterEditing(bodyOffset: Int? = null) { editing = true; focusTitle = bodyOffset == null; bodyFocus = bodyOffset }
    suspend fun flush() = repository.flush()
    fun back() {
        if (screen == Screen.IMAGE) { imageId = null; screen = Screen.NOTE; return }
        if (screen != Screen.NOTE) { screen = Screen.LIST; query = ""; results = emptyList(); choosingDestination = false; return }
        task(null, "Changes could not be saved. Please free some storage before leaving this note.") {
            flush()
            val discard = newDraft && note?.isEmpty == true
            if (discard) note?.let { repository.delete(it.id) }
            endSession()
            if (editing && !discard) { editing = false; focusTitle = false; bodyFocus = null }
            else { screen = Screen.LIST; note = null }
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
            repository.delete(id); history.clear(); note = null; screen = Screen.LIST; reloadAttachments()
        }
    }
    fun search(value: String) {
        query = value; searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            try { results = repository.search(value) } catch (_: CancellationException) { } catch (_: Exception) { error = "Search could not be completed." }
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
        task("Importing photo…", "The photo could not be imported. Unlock again to retry, or discard the unfinished capture.") {
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
        if (destination == null) create() else { open(destination); editing = true }
        incoming.text?.takeIf { it.isNotBlank() }?.let { text ->
            val n = note!!
            change(n.copy(document = DocumentEdits.appendText(n.document, text)))
        }
        if (incoming.uris.isNotEmpty()) import(incoming.uris, incoming.mime)
    }
    /** The passphrase that encrypted the selected backup; it becomes this store's recovery passphrase. */
    fun submitPassword(password: CharArray) {
        passwordMode = null
        task("Validating backup…", "The backup could not be restored. Check the passphrase, file integrity, version, and available storage.") {
            try { restoreCount = backups.stage(checkNotNull(restoreUri), password, ::progress) } finally { password.fill('\u0000') }
        }
        operation?.invokeOnCompletion { password.fill('\u0000') }
    }
    fun selectRestore(uri: Uri?) {
        restoreAfterUnlock = false
        if (uri == null) return
        task("Checking backup…", "This file is not a supported Secure Notes backup, is incomplete, or could not be read.") {
            backups.checkHeader(uri); restoreUri = uri; passwordMode = "restore"
        }
    }
    fun confirmPassphrase() { task(null, "The confirmation could not be saved.") { repository.confirmPassphrase() } }
    /** Called by the activity only after a fresh vault-key authentication. */
    fun revealPassphrase() { task(null, "The recovery passphrase could not be read.") { revealedWords = repository.passphrase().split(' ') } }
    private val unusableLocation = "That location can't be used for automatic backups. Choose a file in another location, for example in Files or Dropbox."
    fun chooseBackup(uri: Uri?) {
        if (uri == null) return
        task("Checking backup file…", unusableLocation) {
            if (app.autoBackup.containsBackup(uri)) replaceBackup = uri
            else { flush(); app.autoBackup.choose(uri) }
        }
    }
    fun confirmReplaceBackup() {
        val uri = replaceBackup ?: return
        replaceBackup = null
        task("Saving encrypted backup…", unusableLocation) { flush(); app.autoBackup.choose(uri) }
    }
    fun restoreChosenBackup() { val uri = replaceBackup ?: return; replaceBackup = null; selectRestore(uri) }
    fun backupNow() { task("Saving encrypted backup…", "The backup could not be saved.") { flush(); app.autoBackup.run(force = true, progress = ::progress) } }
    fun turnOffBackup() { task(null, "Automatic backup settings could not be saved.") { app.autoBackup.turnOff() } }
    fun restore() {
        restoreCount = null
        task("Replacing notes…", "Restore could not be completed. Existing data has been retained where possible.") {
            backups.commit(); reloadAttachments(); screen = Screen.LIST; app.startIndexing()
        }
    }
    fun cancelRestore() { backups.discard(); restoreCount = null }
    override fun onCleared() { backups.discard() }
}
