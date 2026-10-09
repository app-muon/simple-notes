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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import dev.securenotes.security.Crypto
import dev.securenotes.security.Passphrase
import kotlinx.serialization.encodeToString

class NotesRepository(private val context: Context, private val checkAccess: () -> Unit) {
    val notes = MutableStateFlow<List<Note>>(emptyList())
    private val storedTags = MutableStateFlow<List<Tag>>(emptyList())
    val tags = storedTags.asStateFlow()
    val issue = MutableStateFlow<String?>(null)
    /** Bumped after every committed content change; drives automatic backup. */
    val changes = MutableStateFlow(0L)
    /** Null while closed; false until the user has proven they saved the recovery passphrase. */
    val passphraseConfirmed = MutableStateFlow<Boolean?>(null)
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
            opened.orderedNotes() // Capture the legacy display order before replaying pending edits.
            recoverEdits(opened)
            notes.value = opened.orderedNotes()
            storedTags.value = opened.dao.tags().map { it.decode() }
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
    fun hasPendingEdits() = pending.snapshot().isNotEmpty()
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
                root?.fill(0); root = null; notes.value = emptyList(); storedTags.value = emptyList(); passphraseConfirmed.value = null
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
    fun hasVault() = active.exists()
    /** Unwraps the published generation's root with the recovery passphrase, without opening the vault. */
    fun recoverRoot(passphrase: CharArray): ByteArray {
        val id = android.util.AtomicFile(active).readFully().toString(Charsets.UTF_8).also { require(validId(it)) }
        val wrapped = android.util.AtomicFile(File(File(generations, id), "recovery.key")).readFully()
        return Passphrase.unwrap(wrapped, passphrase, Passphrase.recoveryAad(id))
    }
    /** Wraps this session's root under [passphrase] for [v]'s generation and stores the passphrase for later display. */
    suspend fun setPassphrase(v: Vault, passphrase: String, confirmed: Boolean) {
        val chars = passphrase.toCharArray()
        try { atomicWrite(v.recoveryFile, Passphrase.wrap(checkNotNull(root), chars, Passphrase.recoveryAad(v.id))) } finally { chars.fill('\u0000') }
        v.dao.putSecret(SecretRow("passphrase", passphrase.toByteArray(Charsets.UTF_8)))
        if (confirmed) v.dao.putSecret(SecretRow("passphrase-confirmed", byteArrayOf(1))) else v.dao.deleteSecret("passphrase-confirmed")
        if (v === vault) passphraseConfirmed.value = confirmed
    }
    /** Creates the recovery passphrase on first unlock; returns whether the user has confirmed saving it. */
    suspend fun ensurePassphrase(generate: () -> String): Boolean = access { v ->
        if (v.passphrase() == null || !v.recoveryFile.exists()) setPassphrase(v, v.passphrase() ?: generate(), false)
        v.passphraseConfirmed().also { passphraseConfirmed.value = it }
    }
    suspend fun passphrase(): String = access { checkNotNull(it.passphrase()) }
    suspend fun confirmPassphrase() = access { v ->
        v.dao.putSecret(SecretRow("passphrase-confirmed", byteArrayOf(1))); passphraseConfirmed.value = true
    }
    suspend fun save(note: Note, preserveEmpty: Boolean = false) = access { v -> saveSnapshot(v, note, preserveEmpty) }
    private suspend fun saveSnapshot(v: Vault, snapshot: Note, preserveEmpty: Boolean) {
        // A queued snapshot or undo may predate global tag deletion. Never resurrect that assignment.
        val validTags = v.dao.tags().map { it.id }.toSet()
        val note = snapshot.copy(tagIds = snapshot.tagIds.filter { it in validTags }.distinct())
        note.document.validate()
        val existing = v.dao.note(note.id)
        if (existing != null && existing.updatedAt > note.updatedAt) return
        if (note.isEmpty && existing == null && !preserveEmpty) return
        v.database.withTransaction { saveIn(v, note, validTags) }
        changes.value++
        notes.value = if (notes.value.any { it.id == note.id }) notes.value.map { if (it.id == note.id) note else it }
            else listOf(note) + notes.value
    }
    suspend fun saveIn(v: Vault, note: Note, validTagIds: Set<String>) {
        require(note.tagIds.distinct().size == note.tagIds.size && validTagIds.containsAll(note.tagIds))
        if (v.dao.note(note.id) == null) {
            val order = v.noteOrder() ?: v.orderedNotes().map(Note::id)
            v.setNoteOrder(listOf(note.id) + order.filterNot { it == note.id })
        }
        v.dao.put(NoteRow.of(note))
        val attachments = note.document.attachments.toSet()
        val sourceIds = setOf("${note.id}:title", "${note.id}:body") + attachments.map { "${note.id}:file:$it" }
        v.dao.sources(note.id).filter { if (it.kind == "extracted") it.attachmentId !in attachments else it.id !in sourceIds }.forEach { removeSource(v, it.id) }
        addSource(v, SourceRow("${note.id}:title", note.id, null, "title", note.title))
        addSource(v, SourceRow("${note.id}:body", note.id, null, "body", note.document.text))
        note.document.attachments.forEach { id ->
            val a = v.dao.attachment(id) ?: error("Missing attachment")
            require(a.noteId == note.id)
            addSource(v, SourceRow("${note.id}:file:$id", note.id, a.id, "filename", a.filename))
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
    suspend fun refresh(v: Vault) { notes.value = v.orderedNotes(); storedTags.value = v.dao.tags().map { it.decode() } }
    suspend fun createTag(name: String): Tag = access { v ->
        val tag = Tag(name = validateTagName(name, v.dao.tags().map { it.decode() }))
        v.dao.insertTag(TagRow.of(tag)); storedTags.value = v.dao.tags().map { it.decode() }; changes.value++; tag
    }
    suspend fun renameTag(id: String, name: String) = access { v ->
        val all = v.dao.tags().map { it.decode() }
        require(all.any { it.id == id }) { "This tag no longer exists." }
        check(v.dao.updateTag(TagRow.of(Tag(id, validateTagName(name, all, id)))) == 1) { "This tag no longer exists." }
        storedTags.value = v.dao.tags().map { it.decode() }; changes.value++
    }
    suspend fun deleteTag(id: String) = access { v ->
        flushAccepted(v)
        v.database.withTransaction {
            v.dao.notes().map { it.decode() }.filter { id in it.tagIds }.forEach { note ->
                v.dao.put(NoteRow.of(note.copy(tagIds = note.tagIds - id)))
            }
            v.dao.deleteTag(id)
        }
        refresh(v); changes.value++
    }
    suspend fun reorderVisible(ids: List<String>) = access { v ->
        flushAccepted(v)
        val current = v.orderedNotes().map(Note::id)
        v.database.withTransaction { v.setNoteOrder(reorderSubset(current, ids)) }
        refresh(v); changes.value++
    }
    suspend fun delete(id: String) = access { v ->
        v.database.withTransaction {
            v.dao.sources(id).forEach { removeSource(v, it.id) }; v.dao.deleteNote(id)
            v.setNoteOrder(v.noteOrder().orEmpty().filterNot { it == id })
        }
        v.dao.imports().filter { it.noteId == id }.forEach { v.dao.deleteImport(it.id) }
        v.dao.attachments().filter { it.noteId == id }.forEach { v.blob(it.id).delete(); v.dao.deleteAttachment(it.id) }
        changes.value++
        refresh(v)
    }
    suspend fun collectGarbage(retain: Set<String> = emptySet()) = access { garbageCollect(it, retain) }
    private suspend fun garbageCollect(v: Vault, retain: Set<String> = emptySet()) {
        val used = v.dao.notes().flatMap { it.decode().document.attachments }.toSet() + retain
        v.dao.attachments().filter { it.id !in used }.forEach { a -> v.blob(a.id).delete(); v.dao.deleteAttachment(a.id) }
        v.blobs.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }
    suspend fun import(note: Note, uri: Uri, mimeHint: String? = null, importId: String = newId()): Note = access { v ->
        v.dao.attachment(importId)?.let { return@access checkNotNull(v.dao.note(it.noteId)).decode() }
        require(uri.scheme == "content")
        check(uri.authority != "${context.packageName}.attachments")
        var filename = "Attachment"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) filename = c.getString(0)?.take(512)?.ifBlank { "Attachment" } ?: filename
        }
        val mime = context.contentResolver.getType(uri) ?: mimeHint ?: "application/octet-stream"
        val job = ImportRow(importId, note.id, uri.toString(), filename, mime)
        val validTags = v.dao.tags().map { it.id }.toSet()
        val current = note.copy(tagIds = note.tagIds.filter(validTags::contains))
        v.database.withTransaction { saveIn(v, current, validTags); v.dao.putImport(job) }
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
        val result = note.copy(document = note.document.copy(attachments = note.document.attachments.filterNot { it == job.id } + job.id), updatedAt = maxOf(System.currentTimeMillis(), note.updatedAt + 1))
        val validTags = v.dao.tags().map { it.id }.toSet()
        v.database.withTransaction { v.dao.putAttachment(attachment); saveIn(v, result, validTags); v.dao.deleteImport(job.id) }
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        changes.value++
        refresh(v); return result
    }
    suspend fun attachments(): Map<String, AttachmentRow> = access { it.dao.attachments().associateBy { a -> a.id } }
    suspend fun search(query: String, filter: NoteFilter = NoteFilter()): List<SearchHit> = access { v ->
        val terms = SearchText.tokens(query).map { it.term }.distinct().take(20)
        if (terms.isEmpty()) return@access emptyList()
        val matches = mutableMapOf<String, MutableSet<String>>()
        val best = mutableMapOf<String, SearchHit>()
        val sources = mutableMapOf<String, SourceRow?>()
        val noteMap = notes.value.filter(filter::includes).associateBy { it.id }
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
                val kind = when (source.kind) { "title" -> HitKind.TITLE; "body" -> HitKind.BODY; else -> HitKind.ATTACHMENT }
                if (kind == HitKind.ATTACHMENT && source.attachmentId !in note.document.attachments) continue
                val score = base * 10 + when (source.kind) { "title" -> 4; "body" -> 3; "filename" -> 2; else -> 1 }
                matches.getOrPut(note.id) { mutableSetOf() }.add(term)
                if ((best[note.id]?.score ?: 0) < score) {
                    best[note.id] = SearchHit(note.id, note.displayTitle, kind, source.attachmentId,
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
                    addSource(v, SourceRow("${a.id}:${a.nextPage}:$index", a.noteId, a.id, "extracted", chunk))
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
        val restoredNotes = staged.orderedNotes()
        val restoredTags = staged.dao.tags().map { it.decode() }
        // SQLite checkpoint before publishing the generation pointer.
        staged.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        checkAccess(); currentCoroutineContext().ensureActive()
        atomicWrite(active, staged.id.toByteArray())
        // After publication, cleanup failure must never make the staged vault deletable.
        vault = staged; notes.value = restoredNotes; storedTags.value = restoredTags
        passphraseConfirmed.value = runCatching { staged.passphraseConfirmed() }.getOrDefault(false); changes.value++
        pending.clear(); encryptedRecovery = null
        runCatching { old?.close() }
        runCatching { cleanupGenerations(staged.id) }
    }
    private fun cleanupGenerations(keep: String) {
        generations.listFiles()?.filter { it.isDirectory && validId(it.name) && it.name != keep }?.forEach { it.deleteRecursively() }
    }
}
