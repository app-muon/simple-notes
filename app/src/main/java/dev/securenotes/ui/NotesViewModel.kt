package dev.securenotes.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.securenotes.NotesApplication
import dev.securenotes.backup.BackupService
import dev.securenotes.document.*
import dev.securenotes.search.SearchHit
import dev.securenotes.storage.AttachmentRow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class Screen { LIST, NOTE, SEARCH, SETTINGS, IMAGE }
data class SharedContent(val text: String?, val uris: List<Uri>, val mime: String?)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as NotesApplication
    val repository = app.repository
    val backups = BackupService(app, repository, app::checkAccess)
    var screen by mutableStateOf(Screen.LIST)
    var note by mutableStateOf<Note?>(null)
    var editing by mutableStateOf(false)
    var newDraft by mutableStateOf(false)
    var focusTitle by mutableStateOf(false)
    var focusedBlock by mutableStateOf<String?>(null)
    var focusOffset by mutableStateOf<Int?>(null)
    var attachments by mutableStateOf<Map<String, AttachmentRow>>(emptyMap())
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SearchHit>>(emptyList())
    var hit by mutableStateOf<SearchHit?>(null)
    var imageId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var backupFile by mutableStateOf<File?>(null)
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
                busy = null; passwordMode = null; restoreCount = null
            } else reloadAttachments()
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
        note = Note(); newDraft = true; editing = true; focusTitle = true; focusedBlock = null; focusOffset = null
        history.clear(); historyRevision++; hit = null; screen = Screen.NOTE
    }
    fun open(value: Note, match: SearchHit? = null) {
        note = value; newDraft = false; editing = false; focusTitle = false; focusedBlock = null
        history.clear(); historyRevision++; hit = match; screen = Screen.NOTE
    }
    fun change(value: Note, record: Boolean = true) {
        val old = note ?: return
        if (old == value) return
        if (record && editing) history.record(old)
        historyRevision++
        val updated = value.copy(updatedAt = maxOf(System.currentTimeMillis(), old.updatedAt + 1))
        try { repository.enqueue(updated, !newDraft); note = updated }
        catch (_: CancellationException) { app.lock() }
    }
    fun block(value: Block) { note?.let { change(it.copy(document = it.document.copy(blocks = it.document.blocks.map { b -> if (b.id == value.id) value else b }))) } }
    fun format(type: BlockType) {
        val n = note ?: return
        val id = focusedBlock ?: n.document.blocks.firstOrNull { !it.isAttachment }?.id
        if (id == null) addText(type) else n.document.blocks.firstOrNull { it.id == id }?.let { block(it.copy(type = type)); focusTitle = false; focusedBlock = id }
    }
    fun addText(type: BlockType = BlockType.PARAGRAPH) {
        val n = note ?: return
        val block = Block(type = type)
        change(n.copy(document = n.document.copy(blocks = n.document.blocks + block)))
        focusedBlock = block.id; focusTitle = false; focusOffset = 0
    }
    fun split(id: String, text: String, bold: List<BoldSpan>) {
        val n = note ?: return
        val index = n.document.blocks.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = n.document.blocks[index]
        val pieces = text.split('\n')
        if (pieces.size == 1) { block(old.copy(text = text, bold = bold)); return }
        var offset = 0
        val replacement = pieces.mapIndexed { i, piece ->
            val spans = bold.mapNotNull { s ->
                val start = maxOf(s.start, offset) - offset; val end = minOf(s.end, offset + piece.length) - offset
                if (end > start) BoldSpan(start, end) else null
            }
            offset += piece.length + 1
            old.copy(id = if (i == 0) id else newId(), text = piece, bold = spans,
                type = if (i > 0 && (old.type in listOf(BlockType.HEADING1, BlockType.HEADING2) || (old.text.isEmpty() && piece.isEmpty()))) BlockType.PARAGRAPH else old.type,
                checked = if (i == 0) old.checked else false)
        }
        change(n.copy(document = n.document.copy(blocks = n.document.blocks.toMutableList().apply { removeAt(index); addAll(index, replacement) })))
        focusedBlock = replacement.last().id; focusOffset = 0
    }
    fun joinPrevious(id: String) {
        val n = note ?: return
        val index = n.document.blocks.indexOfFirst { it.id == id }
        if (index <= 0) return
        val previous = n.document.blocks[index - 1]; val current = n.document.blocks[index]
        if (previous.isAttachment || current.isAttachment) return
        val merged = previous.copy(text = previous.text + current.text, bold = previous.bold + current.bold.map { BoldSpan(it.start + previous.text.length, it.end + previous.text.length) })
        change(n.copy(document = n.document.copy(blocks = n.document.blocks.toMutableList().apply { set(index - 1, merged); removeAt(index) })))
        focusedBlock = previous.id; focusOffset = previous.text.length
    }
    fun move(id: String, delta: Int) {
        val n = note ?: return
        val index = n.document.blocks.indexOfFirst { it.id == id }; val target = index + delta
        if (index < 0 || target !in n.document.blocks.indices) return
        val blocks = n.document.blocks.toMutableList().apply { add(target, removeAt(index)) }
        change(n.copy(document = n.document.copy(blocks = blocks)))
    }
    fun removeBlock(id: String) { note?.let { n -> change(n.copy(document = n.document.copy(blocks = n.document.blocks.filter { it.id != id }))) } }
    fun undo() { note?.let { n -> history.undo(n)?.let { change(it, false) } } }
    fun redo() { note?.let { n -> history.redo(n)?.let { change(it, false) } } }
    fun enterEditing(id: String? = null) { editing = true; focusedBlock = id; focusTitle = id == null; focusOffset = null }
    suspend fun flush() = repository.flush()
    fun back() {
        if (screen == Screen.IMAGE) { imageId = null; screen = Screen.NOTE; return }
        if (screen != Screen.NOTE) { screen = Screen.LIST; query = ""; results = emptyList(); choosingDestination = false; return }
        task(null, "Changes could not be saved. Please free some storage before leaving this note.") {
            flush()
            val discard = newDraft && note?.isEmpty == true
            if (discard) note?.let { repository.delete(it.id) }
            endSession()
            if (editing && !discard) { editing = false; focusTitle = false; focusedBlock = null }
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
            val blocks = if (n.document.blocks.size == 1 && n.document.blocks.first().text.isEmpty() && !n.document.blocks.first().isAttachment)
                listOf(n.document.blocks.first().copy(text = text)) else n.document.blocks + Block(text = text)
            change(n.copy(document = n.document.copy(blocks = blocks)))
        }
        if (incoming.uris.isNotEmpty()) import(incoming.uris, incoming.mime)
    }
    fun submitPassword(password: CharArray) {
        val mode = passwordMode; passwordMode = null
        task(if (mode == "restore") "Validating backup…" else "Preparing backup…", if (mode == "restore") "The backup could not be restored. Check the password, file integrity, version, and available storage." else "The backup operation failed. Check the password and available storage.") {
            try {
                when (mode) {
                    "configure" -> backups.configure(password)
                    "configure-backup" -> { flush(); backups.configure(password); backupFile = backups.create(password, ::progress) }
                    "backup" -> { flush(); backupFile = backups.create(password, ::progress) }
                    "restore" -> restoreCount = backups.stage(checkNotNull(restoreUri), password, ::progress)
                }
            } finally { password.fill('\u0000') }
        }
        operation?.invokeOnCompletion { password.fill('\u0000') }
    }
    fun selectRestore(uri: Uri?) {
        if (uri == null) return
        task("Checking backup…", "This file is not a supported Secure Notes backup, is incomplete, or could not be read.") {
            backups.checkHeader(uri); restoreUri = uri; passwordMode = "restore"
        }
    }
    fun requestBackup() { task("Checking backup settings…", "Backup settings could not be read.") { passwordMode = if (backups.configured()) "backup" else "configure-backup" } }
    fun exportBackup(uri: Uri?) {
        val file = backupFile ?: return
        backupFile = null
        if (uri == null) { file.delete(); return }
        task("Writing encrypted backup…", "The backup could not be written to that location.") {
            try { withContext(Dispatchers.IO) { val operationContext = currentCoroutineContext(); app.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { backups.copyChecked(it, out) { operationContext.ensureActive() } } } } }
            catch (e: Exception) { runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }; throw e }
            finally { file.delete() }
        }
    }
    fun restore() {
        restoreCount = null
        task("Replacing notes…", "Restore could not be completed. Existing data has been retained where possible.") {
            backups.commit(); reloadAttachments(); screen = Screen.LIST; app.startIndexing()
        }
    }
    fun cancelRestore() { backups.discard(); restoreCount = null }
    override fun onCleared() { backups.discard() }
}
