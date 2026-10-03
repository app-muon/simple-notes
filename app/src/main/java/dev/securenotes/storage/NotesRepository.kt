package dev.securenotes.storage

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dev.securenotes.document.*
import dev.securenotes.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import dev.securenotes.security.Crypto
import kotlinx.serialization.encodeToString

class NotesRepository(private val context: Context, private val checkAccess: () -> Unit) {
    val notes = MutableStateFlow<List<Note>>(emptyList())
    val sort = MutableStateFlow(SortOrder.EDITED)
    val issue = MutableStateFlow<String?>(null)
    val mutex = Mutex()
    private var root: ByteArray? = null
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = PendingEdits(saveScope) {
        try { flush() }
        catch (_: CancellationException) { /* Lock owns the final flush. */ }
        catch (_: Exception) { issue.value = "Your latest changes could not be saved. Free some storage and try again." }
    }
    @Volatile private var acceptingEdits = false
    private var encryptedRecovery: Pair<String, ByteArray>? = null
    @Volatile var vault: Vault? = null
        private set
    private val generations = File(context.noBackupFilesDir, "vaults").apply { mkdirs() }
    private val active = File(context.noBackupFilesDir, "active")
    suspend fun open(key: ByteArray) = withContext(Dispatchers.IO) { mutex.withLock {
        if (vault != null) return@withLock
        root = key.copyOf()
        val id = if (active.exists()) android.util.AtomicFile(active).readFully().toString(Charsets.UTF_8).also { require(validId(it)) } else newId()
        val directory = File(generations, id)
        if (active.exists()) check(File(directory, "notes.db").exists()) { "Vault unavailable" }
        directory.mkdirs()
        val opened = Vault(context, directory, key, checkAccess)
        try {
            recoverEdits(opened)
            notes.value = opened.dao.notes().map { it.decode() }
            sort.value = opened.sort()
            if (!active.exists()) atomicWrite(active, id.toByteArray())
            cleanupGenerations(id)
            garbageCollect(opened)
            vault = opened
            acceptingEdits = true; pending.start()
        } catch (e: Exception) { opened.close(); root?.fill(0); root = null; throw e }
    } }
    @Synchronized fun freezeEdits() { acceptingEdits = false; pending.stop() }
    @Synchronized fun enqueue(note: Note, preserveEmpty: Boolean = false) {
        checkAccess(); check(acceptingEdits)
        pending.put(PendingEdit(note, preserveEmpty))
    }
    suspend fun flush() = access { flushAccepted(it) }
    /** Only previously accepted snapshots can be written here after the session is revoked. */
    suspend fun close() {
        freezeEdits()
        withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
            val current = vault
            try {
                if (current != null) {
                    try { flushAccepted(current) }
                    catch (_: Exception) { preserveEdits(current) }
                }
            } finally {
                pending.clear(); current?.close(); vault = null
                root?.fill(0); root = null; notes.value = emptyList()
            }
        } }
    }
    private suspend fun flushAccepted(v: Vault) {
        for (edit in pending.snapshot()) {
            saveSnapshot(v, edit.note, edit.preserveEmpty)
            pending.committed(edit)
        }
    }
    private fun preserveEdits(v: Vault) {
        val key = Crypto.subkey(checkNotNull(root), "pending:${v.id}")
        val clear = documentJson.encodeToString(pending.snapshot()).toByteArray()
        try {
            val sealed = Crypto.seal(key, clear, v.id.toByteArray())
            encryptedRecovery = v.id to sealed
            try {
                atomicWrite(File(v.directory, "pending.edits"), sealed)
                issue.value = "Recent edits need recovery. Unlock again to retry saving them."
            } catch (_: Exception) {
                issue.value = "Storage is full or unavailable. Recent edits are held encrypted in memory only. Free storage and unlock again before closing the app."
            }
        } finally { key.fill(0); clear.fill(0) }
    }
    private suspend fun recoverEdits(v: Vault) {
        val journal = File(v.directory, "pending.edits")
        val sealed = encryptedRecovery?.takeIf { it.first == v.id }?.second
            ?: if (journal.exists()) android.util.AtomicFile(journal).readFully() else return
        val key = Crypto.subkey(checkNotNull(root), "pending:${v.id}")
        val clear = try { Crypto.open(key, sealed, v.id.toByteArray()) } finally { key.fill(0) }
        try {
            documentJson.decodeFromString<List<PendingEdit>>(clear.toString(Charsets.UTF_8)).forEach {
                saveSnapshot(v, it.note, it.preserveEmpty)
            }
            journal.delete(); encryptedRecovery = null
        } finally { clear.fill(0) }
    }
    suspend fun <T> access(block: suspend (Vault) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { checkAccess(); block(checkNotNull(vault)) }
    }
    suspend fun save(note: Note, preserveEmpty: Boolean = false) = access { v -> saveSnapshot(v, note, preserveEmpty) }
    private suspend fun saveSnapshot(v: Vault, note: Note, preserveEmpty: Boolean) {
        note.document.validate()
        val existing = v.dao.note(note.id)
        if (existing != null && existing.updatedAt > note.updatedAt) return
        if (note.isEmpty && existing == null && !preserveEmpty) return
        v.database.withTransaction { saveIn(v, note) }
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
    suspend fun saveIn(v: Vault, note: Note) {
        v.dao.put(NoteRow.of(note))
        val attachments = note.document.blocks.mapNotNull { it.attachmentId }.toSet()
        val sourceIds = note.document.blocks.map { it.id }.toSet() + "${note.id}:title"
        v.dao.sources(note.id).filter { if (it.kind == "extracted") it.attachmentId !in attachments else it.id !in sourceIds }.forEach { removeSource(v, it.id) }
        addSource(v, SourceRow("${note.id}:title", note.id, null, null, "title", note.title))
        note.document.blocks.forEach { b ->
            if (b.isAttachment) {
                val a = v.dao.attachment(b.attachmentId!!) ?: error("Missing attachment")
                require(a.noteId == note.id)
                addSource(v, SourceRow(b.id, note.id, b.id, a.id, "filename", a.filename))
            } else addSource(v, SourceRow(b.id, note.id, b.id, null, "body", b.text))
        }
    }
    suspend fun addSource(v: Vault, source: SourceRow) {
        if (v.dao.source(source.id) == source) return
        v.dao.deletePostings(source.id); v.dao.putSource(source)
        SearchText.tokens(source.text).chunked(500).forEach { tokens ->
            v.dao.putPostings(tokens.map { PostingRow(sourceId = source.id, term = it.term, length = it.term.length, start = it.start, end = it.end) })
        }
    }
    private suspend fun removeSource(v: Vault, id: String) { v.dao.deletePostings(id); v.dao.deleteSource(id) }
    suspend fun refresh(v: Vault) { notes.value = v.dao.notes().map { it.decode() }; sort.value = v.sort() }
    suspend fun setSort(order: SortOrder) = access { it.setSort(order); sort.value = order }
    suspend fun delete(id: String) = access { v ->
        v.database.withTransaction { v.dao.sources(id).forEach { removeSource(v, it.id) }; v.dao.deleteNote(id) }
        v.dao.imports().filter { it.noteId == id }.forEach { v.dao.deleteImport(it.id) }
        v.dao.attachments().filter { it.noteId == id }.forEach { v.blob(it.id).delete(); v.dao.deleteAttachment(it.id) }
        refresh(v)
    }
    suspend fun collectGarbage(retain: Set<String> = emptySet()) = access { garbageCollect(it, retain) }
    private suspend fun garbageCollect(v: Vault, retain: Set<String> = emptySet()) {
        val used = v.dao.notes().flatMap { it.decode().document.blocks.mapNotNull { b -> b.attachmentId } }.toSet() + retain
        v.dao.attachments().filter { it.id !in used }.forEach { a -> v.blob(a.id).delete(); v.dao.deleteAttachment(a.id) }
        v.blobs.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }
    suspend fun import(note: Note, uri: Uri, mimeHint: String? = null, importId: String = newId(), blockId: String = newId()): Note = access { v ->
        v.dao.attachment(importId)?.let { return@access checkNotNull(v.dao.note(it.noteId)).decode() }
        require(uri.scheme == "content")
        check(uri.authority != "${context.packageName}.attachments")
        var filename = "Attachment"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) filename = c.getString(0)?.take(512)?.ifBlank { "Attachment" } ?: filename
        }
        val mime = context.contentResolver.getType(uri) ?: mimeHint ?: "application/octet-stream"
        val job = ImportRow(importId, note.id, uri.toString(), filename, mime, blockId)
        v.database.withTransaction { saveIn(v, note); v.dao.putImport(job) }
        try {
            completeImport(v, job)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                v.dao.deleteImport(job.id); v.blob(job.id).delete()
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            throw e
        }
    }
    private suspend fun completeImport(v: Vault, job: ImportRow): Note {
        val operation = currentCoroutineContext()
        val note = checkNotNull(v.dao.note(job.noteId)).decode()
        val uri = Uri.parse(job.uri)
        val (size, hash) = context.contentResolver.openInputStream(uri)!!.use { v.write(job.id, it) { operation.ensureActive() } }
        val attachment = AttachmentRow(job.id, note.id, job.filename, job.mime, size, hash)
        val block = Block(id = job.blockId, type = if (job.mime.startsWith("image/")) BlockType.IMAGE else BlockType.FILE, attachmentId = job.id)
        val result = note.copy(document = note.document.copy(blocks = note.document.blocks + block), updatedAt = maxOf(System.currentTimeMillis(), note.updatedAt + 1))
        v.database.withTransaction { v.dao.putAttachment(attachment); saveIn(v, result); v.dao.deleteImport(job.id) }
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        refresh(v); return result
    }
    suspend fun attachments(): Map<String, AttachmentRow> = access { it.dao.attachments().associateBy { a -> a.id } }
    suspend fun search(query: String): List<SearchHit> = access { v ->
        val terms = SearchText.tokens(query).map { it.term }.distinct().take(20)
        if (terms.isEmpty()) return@access emptyList()
        val matches = mutableMapOf<String, MutableSet<String>>()
        val best = mutableMapOf<String, SearchHit>()
        val sources = mutableMapOf<String, SourceRow?>()
        val noteMap = notes.value.associateBy { it.id }
        for (term in terms) {
            val matchingTerms = mutableSetOf<String>()
            var after = ""
            do {
                val page = v.dao.prefixTerms("$term%", after)
                matchingTerms += page; after = page.lastOrNull() ?: after
            } while (page.size == 512)
            if (term.length >= 4) {
                after = ""
                do {
                    val page = v.dao.fuzzyTerms(term.length - 1, term.length + 1, after)
                    matchingTerms += page.filter { SearchText.score(term, it) > 0 }
                    after = page.lastOrNull() ?: after
                    currentCoroutineContext().ensureActive(); checkAccess()
                } while (page.size == 512)
            }
            for (candidate in matchingTerms) {
              var afterId = 0L
              do {
                val page = v.dao.postings(candidate, afterId)
                for (posting in page) {
                val base = SearchText.score(term, posting.term)
                if (base == 0) continue
                val source = sources.getOrPut(posting.sourceId) { v.dao.source(posting.sourceId) } ?: continue
                val note = noteMap[source.noteId] ?: continue
                val blockId = if (source.kind == "extracted") note.document.blocks.firstOrNull { it.attachmentId == source.attachmentId }?.id ?: continue else source.blockId
                val score = base * 10 + when (source.kind) { "title" -> 4; "body" -> 3; "filename" -> 2; else -> 1 }
                matches.getOrPut(note.id) { mutableSetOf() }.add(term)
                if ((best[note.id]?.score ?: 0) < score) {
                    best[note.id] = SearchHit(note.id, note.displayTitle, blockId, source.attachmentId,
                        posting.start + source.offset, posting.end + source.offset,
                        SearchText.snippet(source.text, posting.start, posting.end),
                        source.attachmentId?.let { v.dao.attachment(it)?.filename }, score)
                }
                }
                afterId = page.lastOrNull()?.id ?: afterId
                currentCoroutineContext().ensureActive(); checkAccess()
              } while (page.size == 512)
            }
        }
        best.values.filter { matches[it.noteId]?.size == terms.size }.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.title })
    }
    /** One checkpoint per call; no worker can reopen the vault without an authenticated session. */
    suspend fun indexNext(): Boolean = access { v ->
        v.dao.imports().firstOrNull()?.let { job ->
            try { completeImport(v, job) }
            catch (e: Exception) {
                checkAccess(); currentCoroutineContext().ensureActive()
                v.dao.deleteImport(job.id); v.blob(job.id).delete()
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(job.uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                issue.value = "An interrupted import could not be resumed. Please select that file again."
            }
            return@access true
        }
        val a = v.dao.attachments().firstOrNull { !it.indexed } ?: return@access false
        try {
            var done = true
            var text = ""
            var nextPosition = a.nextPage + 1
            when {
                a.mime == "application/pdf" || a.filename.endsWith(".pdf", true) -> {
                    PdfRenderer(v.descriptor(a.id, a.size)).use { pdf ->
                        if (a.nextPage < pdf.pageCount) pdf.openPage(a.nextPage).use { page ->
                            text = page.textContents.joinToString("\n") { it.text }
                        }
                        done = a.nextPage + 1 >= pdf.pageCount
                    }
                }
                a.mime.startsWith("text/") || a.filename.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "csv") -> v.read(a.id).bufferedReader(Charsets.UTF_8).use { reader ->
                    var left = a.nextPage.toLong()
                    while (left > 0) { val skipped = reader.skip(left); if (skipped == 0L) break; left -= skipped }
                    text = SearchText.readChunk(reader)
                    nextPosition = Math.addExact(a.nextPage, text.length)
                    done = text.length < 8192
                }
            }
            checkAccess()
            v.database.withTransaction {
                SearchText.chunks(text).forEachIndexed { index, chunk ->
                    addSource(v, SourceRow("${a.id}:${a.nextPage}:$index", a.noteId, null, a.id, "extracted", chunk))
                }
                v.dao.putAttachment(a.copy(indexed = done, nextPage = nextPosition))
            }
        } catch (e: Exception) {
            checkAccess() // A lock is a pause, not a permanent extraction failure.
            if (e is CancellationException) throw e
            v.dao.putAttachment(a.copy(indexed = true))
        }
        true
    }
    fun staging(): Vault {
        checkAccess()
        val directory = File(generations, newId()).apply { mkdirs() }
        return Vault(context, directory, checkNotNull(root), checkAccess)
    }
    suspend fun activate(staged: Vault) {
        checkAccess()
        val old = vault
        val restoredNotes = staged.dao.notes().map { it.decode() }
        val restoredSort = staged.sort()
        // SQLite checkpoint before publishing the generation pointer.
        staged.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        checkAccess(); currentCoroutineContext().ensureActive()
        atomicWrite(active, staged.id.toByteArray())
        // After publication, cleanup failure must never make the staged vault deletable.
        vault = staged; notes.value = restoredNotes; sort.value = restoredSort
        pending.clear(); encryptedRecovery = null
        runCatching { old?.close() }
        runCatching { cleanupGenerations(staged.id) }
    }
    private fun cleanupGenerations(keep: String) {
        generations.listFiles()?.filter { it.isDirectory && validId(it.name) && it.name != keep }?.forEach { it.deleteRecursively() }
    }
}
