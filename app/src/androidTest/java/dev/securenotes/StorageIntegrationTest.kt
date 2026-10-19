package dev.securenotes

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.securenotes.backup.AttachmentFile
import dev.securenotes.backup.AttachmentFolder
import dev.securenotes.backup.AttachmentFolderNeeded
import dev.securenotes.backup.BackupCodec
import dev.securenotes.backup.BackupConfiguration
import dev.securenotes.backup.BackupFolder
import dev.securenotes.backup.BackupManifest
import dev.securenotes.backup.BackupMode
import dev.securenotes.backup.BackupService
import dev.securenotes.backup.BackupSlot
import dev.securenotes.backup.PendingSetup
import dev.securenotes.backup.VaultBackupSettings
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.storage.NotesRepository
import dev.securenotes.storage.displayName
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import java.io.File

class StorageIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var repository: NotesRepository
    private lateinit var backup: BackupService
    private val root = Crypto.random(32)
    private val sources = mutableListOf<File>()
    @Volatile private var authorized = true
    /** Replaces the repository's content resolver, to reach a [SaveDestination]. */
    @Volatile private var resolver: ContentResolver? = null
    @Before fun setup() = runBlocking {
        System.loadLibrary("sqlcipher")
        directory = File(context.cacheDir, "test-${newId()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getContentResolver(): ContentResolver = resolver ?: super.getContentResolver()
        }
        repository = NotesRepository(isolated) { if (!authorized) throw CancellationException("Locked") }
        repository.open(root.copyOf())
        repository.ensurePassphrase { phrase }
        backup = BackupService(context, repository) {}
    }
    private val phrase = "cherisher driven greedily motion pyramid skipping unbundle vertigo"
    @Test fun daoTagConflictsAbortWithoutReplacingRows(): Unit = runBlocking {
        val first = repository.createTag("First"); val second = repository.createTag("Second")
        val revision = repository.changes.value
        repository.access { v ->
            val before = v.dao.tags()
            for (row in listOf(dev.securenotes.storage.TagRow.of(Tag(name = "FIRST")), dev.securenotes.storage.TagRow.of(first.copy(name = "Third")))) {
                try { v.dao.insertTag(row); fail("Conflicting insert succeeded") } catch (_: Exception) { }
                assertEquals(before, v.dao.tags())
            }
            try { v.dao.updateTag(dev.securenotes.storage.TagRow.of(second.copy(name = "First"))); fail("Conflicting rename succeeded") } catch (_: Exception) { }
            assertEquals(before, v.dao.tags())
            assertEquals(0, v.dao.updateTag(dev.securenotes.storage.TagRow.of(Tag(name = "Missing"))))
        }
        try { repository.createTag("first"); fail("Duplicate created") } catch (_: IllegalArgumentException) { }
        try { repository.renameTag(second.id, "first"); fail("Duplicate renamed") } catch (_: IllegalArgumentException) { }
        assertEquals(revision, repository.changes.value)
        assertEquals(listOf(first, second), repository.tags.value)
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(first, second), repository.tags.value)
    }
    @Test fun tagsPersistFilterSearchAndPreserveHiddenOrder(): Unit = runBlocking {
        val tag = repository.createTag("  Work  "); val other = repository.createTag("Other")
        val notes = listOf(Note(title = "A needle", tagIds = listOf(tag.id, other.id)), Note(title = "x needle"),
            Note(title = "B needle", tagIds = listOf(tag.id)), Note(title = "y needle"), Note(title = "C needle", tagIds = listOf(tag.id)))
        notes.reversed().forEach { repository.save(it) }
        assertEquals(3, repository.search("needle", NoteFilter(tagId = tag.id)).size)
        assertEquals(2, repository.search("needle", NoteFilter(untagged = true)).size)
        repository.reorderVisible(listOf(notes[4].id, notes[0].id, notes[2].id))
        assertEquals(listOf(notes[4], notes[1], notes[0], notes[3], notes[2]), repository.notes.value)
        repository.renameTag(tag.id, "WORK")
        try { repository.renameTag(other.id, "work"); fail("Accepted duplicate name") } catch (_: IllegalArgumentException) { }
        repository.close(); assertTrue(repository.tags.value.isEmpty())
        repository.open(root.copyOf())
        assertEquals("WORK", repository.tags.value.first { it.id == tag.id }.name)
        assertEquals(listOf(tag.id, other.id), repository.notes.value.first { it.id == notes[0].id }.tagIds)
        repository.enqueue(notes[0].copy(title = "Pending edit", updatedAt = notes[0].updatedAt + 1))
        repository.deleteTag(tag.id)
        assertEquals(5, repository.notes.value.size)
        assertEquals("Pending edit", repository.notes.value.first { it.id == notes[0].id }.title)
        assertTrue(repository.notes.value.none { tag.id in it.tagIds })
        assertEquals(listOf(other.id), repository.notes.value.first { it.id == notes[0].id }.tagIds)
        repository.save(notes[0].copy(title = "Stale assignments", updatedAt = notes[0].updatedAt + 2))
        assertEquals(listOf(other.id), repository.notes.value.first { it.id == notes[0].id }.tagIds)
        val order = repository.notes.value.map(Note::id)
        for (invalid in listOf(listOf(order[0], order[0]), listOf(newId()))) {
            try { repository.reorderVisible(invalid); fail("Invalid order accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(order, repository.notes.value.map(Note::id))
        }
    }
    @Test fun tagBackupsIncludeUnusedDefinitionsAndMetadataChanges(): Unit = runBlocking {
        val first = backup.create({})!!; sources += first.first
        val tag = repository.createTag("Personal"); val unused = repository.createTag("Unused")
        val note = Note(title = "Tagged", tagIds = listOf(tag.id)); repository.save(note)
        val revision = repository.changes.value
        repository.renameTag(unused.id, "Still unused")
        assertTrue(repository.changes.value > revision)
        val second = backup.create({}, first.second)!!; sources += second.first
        assertNotEquals(first.second, second.second)
        assertNull(backup.create({}, second.second))
        repository.deleteTag(tag.id)
        backup.stage(source("tags.ssnb", second.first.readBytes()), phrase.toCharArray()) {}
        backup.commit()
        assertEquals(note, repository.notes.value.single())
        assertEquals(setOf("Personal", "Still unused"), repository.tags.value.map { it.name }.toSet())
    }
    @Test fun additiveMigrationRetainsVersionOneNotesOrderAndSearch(): Unit = runBlocking {
        val note = Note(title = "Migration needle", document = Document(text = "Existing body", bold = listOf(BoldSpan(0, 8))))
        repository.save(note)
        // Recreate the exported v1 schema in this synthetic SQLCipher vault, including its identity hash.
        repository.access { v ->
            val db = v.database.openHelper.writableDatabase
            db.execSQL("ALTER TABLE notes DROP COLUMN tagIds")
            db.execSQL("DROP TABLE tags")
            db.execSQL("UPDATE room_master_table SET identity_hash = '6cd01fd6d9b0fb2e4614552389bf5a07' WHERE id = 42")
            db.execSQL("PRAGMA user_version = 1")
        }
        repository.close(); repository.open(root.copyOf())
        assertEquals(note, repository.notes.value.single())
        assertTrue(repository.tags.value.isEmpty())
        assertEquals(note.id, repository.search("needle").single().noteId)
        val tag = repository.createTag("After upgrade")
        repository.save(note.copy(tagIds = listOf(tag.id)))
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(tag.id), repository.notes.value.single().tagIds)
    }
    private val otherPhrase = "vertigo unbundle skipping pyramid motion greedily driven cherisher"
    @Test fun fingerprintsIgnoreIndexingAndCanonicalizeMetadataButIncludePhraseAndManualOrder() = runBlocking {
        repository.confirmPassphrase()
        val a = repository.createTag("A"); val b = repository.createTag("B")
        val first = repository.import(Note(title = "First", tagIds = listOf(a.id, b.id)), source("index.txt", "searchable content".toByteArray()))
        val second = Note(title = "Second"); repository.save(second)
        val initial = backup.inspect().fingerprint
        while (repository.indexNext()) Unit
        assertEquals(initial, backup.inspect().fingerprint)
        repository.access { v -> v.dao.put(dev.securenotes.storage.NoteRow.of(first.copy(tagIds = listOf(b.id, a.id)))) }
        assertEquals(initial, backup.inspect().fingerprint)
        repository.reorderVisible(listOf(first.id, second.id))
        assertNotEquals(initial, backup.inspect().fingerprint)
        val ordered = backup.inspect().fingerprint
        repository.access { repository.setPassphrase(it, otherPhrase, true) }
        assertNotEquals(ordered, backup.inspect().fingerprint)
    }
    @Test fun restoreCopiesLatestDeviceConfigurationAtPublicationAndPreservesActiveEvidence() = runBlocking {
        repository.save(Note(title = "Restored"))
        val file = backup.create({})!!.first; sources += file
        backup.stage(source("device-config.ssnb", file.readBytes()), phrase.toCharArray()) {}
        val config = dev.securenotes.backup.BackupConfiguration(mode = dev.securenotes.backup.BackupMode.PAIR,
            folder = "content://synthetic/folder", slots = listOf("A", "B").map {
                dev.securenotes.backup.BackupSlot("content://synthetic/$it", "$it.ssnb", 123, "a".repeat(64))
            }, active = 0, phraseHash = dev.securenotes.backup.BackupService.phraseHash(otherPhrase), fingerprint = "b".repeat(64), savedAt = 123)
        repository.access { dev.securenotes.backup.VaultBackupSettings.write(it, config) }
        backup.commit()
        val copied = repository.access { dev.securenotes.backup.VaultBackupSettings.read(it) }
        assertEquals(config.slots, copied.slots); assertEquals(0, copied.active); assertTrue(copied.pending)
        assertEquals(dev.securenotes.backup.BackupProblem.PHRASE_CHANGED, copied.error?.problem)
        assertEquals(phrase, repository.passphrase())
    }
    @Test fun missingEstablishedRecoveryPhraseIsNeverRegenerated() = runBlocking {
        repository.confirmPassphrase()
        repository.access { it.dao.deleteSecret("passphrase") }
        var generated = false
        try { repository.ensurePassphrase { generated = true; otherPhrase }; fail("Replaced established phrase") } catch (_: IllegalStateException) { }
        assertFalse(generated)
        assertArrayEquals(root, repository.recoverRoot(phrase.toCharArray()))
        repository.access { it.recoveryFile.delete(); it.dao.deleteSecret("passphrase-confirmed") }
        try { repository.ensurePassphrase { generated = true; otherPhrase }; fail("Inferred first run from missing words") } catch (_: IllegalStateException) { }
        assertFalse(generated)
    }
    @Test fun backupsOverTwentyMiBAreStreamedAndRestoreAttachmentIntegrity() = runBlocking {
        val payload = ByteArray(21 * 1024 * 1024).also { java.util.Random(42).nextBytes(it) }
        val note = repository.import(Note(title = "Large attachment"), source("large.bin", payload))
        repository.confirmPassphrase()
        backup.prepare({}).use { artifact ->
            assertTrue(artifact.length > 20L * 1024 * 1024)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", File(File(context.cacheDir, "camera"), "large-backup.ssnb").also {
                artifact.file.copyTo(it, overwrite = true); sources += it
            })
            backup.stage(uri, phrase.toCharArray()) {}; backup.commit()
        }
        val attachment = repository.attachments().values.single()
        repository.access { v -> v.read(attachment.id).use { assertEquals(attachment.sha256, dev.securenotes.backup.ciphertextDigest(it)) } }
        assertEquals(note, repository.notes.value.single())
    }
    @After fun cleanup() = runBlocking {
        if (::backup.isInitialized) backup.discard()
        if (::repository.isInitialized) repository.close()
        if (::directory.isInitialized) directory.deleteRecursively()
        sources.forEach { it.delete() }
    }
    private fun source(name: String, bytes: ByteArray): android.net.Uri {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${newId()}-$name")
        file.writeBytes(bytes); sources += file
        return FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
    }
    private fun destination(contents: ByteArray, size: Long?, truncates: Boolean) =
        SaveDestination(context, File(directory, "${newId()}-saved").apply { writeBytes(contents) }, size, truncates).also { resolver = it.resolver }
    @Test fun lockFlushesLatestAcceptedSnapshotEvenWhenNormalAccessIsRevoked() = runBlocking {
        val note = Note(title = "Draft")
        repository.mutex.lock()
        val latest = note.copy(title = "Latest accepted edit", updatedAt = note.updatedAt + 1_000)
        repeat(999) { repository.enqueue(note.copy(title = "Edit $it", updatedAt = note.updatedAt + it)) }
        repository.enqueue(latest)
        authorized = false; repository.freezeEdits()
        val closing = async(Dispatchers.IO) { repository.close() }
        yield(); assertFalse(closing.isCompleted)
        repository.mutex.unlock(); closing.await()
        assertTrue(repository.notes.value.isEmpty())
        authorized = true; repository.open(root.copyOf())
        assertEquals(latest, repository.notes.value.single())
    }
    @Test fun failedLockSaveKeepsEncryptedRecoveryJournalAndReplaysIt() = runBlocking {
        repository.access { it.database.openHelper.writableDatabase.query("PRAGMA query_only=ON").use { cursor -> cursor.moveToFirst() } }
        val note = Note(title = "Recovery private marker")
        repository.enqueue(note); repository.freezeEdits(); authorized = false
        val vaultDirectory = repository.vault!!.directory
        repository.close()
        val journal = File(vaultDirectory, "pending.edits")
        assertTrue(journal.exists())
        assertFalse(journal.readBytes().toString(Charsets.ISO_8859_1).contains(note.title))
        assertNotNull(repository.issue.value)
        authorized = true; repository.open(root.copyOf())
        assertEquals(note, repository.notes.value.single()); assertFalse(journal.exists())
    }
    @Test fun exactPrefixAndFuzzySearchSurviveMoreThanTenThousandPostings() = runBlocking {
        repository.save(Note(title = "Noise", document = Document(text = "zzzzzzz ".repeat(10_050))))
        val target = Note(title = "Target", document = Document(text = "receipt"))
        repository.save(target)
        for (query in listOf("receipt", "rece", "reciept")) assertEquals(target.id, repository.search(query).single().noteId)
        val repeated = Note(title = "Many receipts", document = Document(text = "receipt ".repeat(10_050)))
        repository.save(repeated)
        assertEquals(setOf(target.id, repeated.id), repository.search("receipt").map { it.noteId }.toSet())
    }
    @Test fun repeatedOpenKeepsOneVaultAndDoesNotReplaceTheRoot() = runBlocking {
        val first = repository.vault
        coroutineScope { repeat(5) { launch(Dispatchers.IO) { repository.open(root.copyOf()) } } }
        assertSame(first, repository.vault)
        val note = Note(title = "One vault"); repository.save(note)
        repository.close(); repository.open(root.copyOf())
        assertEquals(note, repository.notes.value.single())
    }
    @Test fun cameraDestinationAndResultSurviveRecreationWithoutDuplicateImport() = runBlocking {
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val camera = dev.securenotes.storage.CameraCapture(isolated, repository)
        val note = Note(title = "Photo destination")
        repository.enqueue(note)
        val uri = camera.prepare(note)
        val bitmap = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        repository.close(); repository.open(root.copyOf())
        val recreated = dev.securenotes.storage.CameraCapture(isolated, repository)
        recreated.cleanOrphans(); recreated.result(true)
        val restored = recreated.finish()!!
        assertEquals(note.id, restored.id); assertEquals(1, restored.document.attachments.size)
        assertNull(recreated.finish()); assertEquals(1, repository.attachments().size)
        assertFalse(recreated.hasPending())
        val photo = repository.attachments().values.single()
        assertTrue(photo.filename, photo.filename.matches(Regex("""Photo \d{4}-\d{2}-\d{2} \d{2}\.\d{2}\.\d{2}\.jpg""")))
        assertEquals("Photo.jpg", photo.copy(filename = "${photo.id}.jpg").displayName)
    }
    @Test fun savedCopyIsPlaintextAndReplacesDestinationContents() = runBlocking {
        val bytes = "Receipt for the shared trip".toByteArray()
        repository.import(Note(title = "Save"), source("receipt.txt", bytes))
        val attachment = repository.attachments().values.single()
        val target = File(File(context.cacheDir, "camera"), "${newId()}-saved.txt").apply { writeBytes(ByteArray(4096) { 1 }); sources += this }
        repository.saveCopy(attachment.id, FileProvider.getUriForFile(context, "${context.packageName}.camera", target))
        assertArrayEquals(bytes, target.readBytes())
    }
    @Test fun saveKeepsDocumentsOfUnknownSizeAndNeverLeavesTrailingBytes() = runBlocking {
        val bytes = "Receipt for the shared trip".toByteArray()
        repository.import(Note(title = "Save"), source("receipt.txt", bytes))
        val attachment = repository.attachments().values.single()
        val existing = ByteArray(4096) { 1 }
        val unknown = destination(existing, size = null, truncates = true)
        repository.discardUnused(unknown.uri)
        assertTrue(unknown.calls.isEmpty()); assertArrayEquals(existing, unknown.file.readBytes())
        for (size in listOf(4096L, null)) {
            val untruncated = destination(existing, size, truncates = false)
            try { repository.saveCopy(attachment.id, untruncated.uri); fail("Wrote without truncating") } catch (_: java.io.IOException) { }
            assertTrue(untruncated.calls.isEmpty()); assertArrayEquals(existing, untruncated.file.readBytes())
        }
        val empty = destination(ByteArray(0), size = 0, truncates = false)
        repository.saveCopy(attachment.id, empty.uri)
        assertTrue(empty.calls.isEmpty()); assertArrayEquals(bytes, empty.file.readBytes())
        val unused = destination(ByteArray(0), size = 0, truncates = true)
        repository.discardUnused(unused.uri)
        assertEquals(listOf(DELETE_DOCUMENT), unused.calls)
    }
    @Test fun saveCancelledOrLockedWhileQueuedDiscardsThePickersDocument() = runBlocking {
        repository.import(Note(title = "Save"), source("queued.txt", "Queued".toByteArray()))
        val attachment = repository.attachments().values.single()
        for (lock in listOf(false, true)) {
            val target = destination(ByteArray(0), size = 0, truncates = true)
            // Holds the mutex as indexing or a backup would.
            val save = repository.mutex.withLock {
                async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repository.saveCopy(attachment.id, target.uri) }
                    .also { if (lock) authorized = false else it.cancel() }
            }
            try { save.await(); fail("Queued save completed") } catch (_: CancellationException) { }
            authorized = true
            assertEquals(listOf(DELETE_DOCUMENT), target.calls)
        }
    }
    @Test fun imageDecoderAppliesExifAndReleasesProxyDescriptors() = runBlocking {
        val picture = File(File(context.cacheDir, "camera").apply { mkdirs() }, "${newId()}.jpg").also { sources += it }
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        picture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        android.media.ExifInterface(picture.absolutePath).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
        }
        repository.import(Note(title = "Rotated"), FileProvider.getUriForFile(context, "${context.packageName}.camera", picture))
        val a = repository.attachments().values.single()
        repository.access { v ->
            repeat(4) { val decoded = v.decodeImage(a.id, a.size, 1200); assertEquals(40, decoded.width); assertEquals(80, decoded.height); decoded.recycle() }
            withTimeout(5_000) { while (v.descriptorCount != 0) delay(10) }
        }
    }
    @Test fun encryptedNotesPersistSearchAndDelete() = runBlocking {
        val note = Note(title = "Holiday", document = Document(text = "Café receipt"))
        repository.save(note)
        assertEquals(note.id, repository.search("cafe reciept").single().noteId)
        val file = repository.vault!!.directory.resolve("notes.db")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("SQLite format"))
        repository.close(); repository.open(root.copyOf())
        assertEquals(note, repository.notes.value.single())
        repository.delete(note.id)
        assertTrue(repository.notes.value.isEmpty()); assertTrue(repository.search("holiday").isEmpty())
    }
    @Test fun attachmentImportExtractionAndSeekableRead() = runBlocking {
        val bytes = "A searchable naïve document about receipts.".toByteArray()
        val note = repository.import(Note(title = "Files"), source("readme.txt", bytes))
        val attachment = repository.attachments().values.single()
        assertEquals(bytes.size.toLong(), attachment.size)
        assertFalse(repository.vault!!.blob(attachment.id).readBytes().contentEquals(bytes))
        while (repository.indexNext()) Unit
        assertEquals(note.id, repository.search("naive").single().noteId)
        repository.access { v ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(v.descriptor(attachment.id, attachment.size)).use { assertArrayEquals(bytes, it.readBytes()) }
        }
        repository.delete(note.id)
        assertFalse(repository.vault!!.blob(attachment.id).exists())
        assertTrue(repository.attachments().isEmpty())
    }
    @Test fun pdfTextExtractionUsesPlatformParser() = runBlocking {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 300, 1).create())
        page.canvas.drawText("Receipt holiday", 20f, 50f, Paint().apply { textSize = 20f })
        pdf.finishPage(page)
        val data = java.io.ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray(); pdf.close()
        repository.import(Note(title = "Document"), source("sample.pdf", data))
        while (repository.indexNext()) Unit
        assertEquals("Document", repository.search("reciept").single().title)
    }
    @Test fun textExtractionResumesWithoutSplittingBoundaryWords() = runBlocking {
        val text = "x ".repeat(4094) + "receipt " + "y ".repeat(4200) + "complete"
        repository.import(Note(title = "Long document"), source("long.txt", text.toByteArray()))
        assertTrue(repository.indexNext())
        repository.close(); repository.open(root.copyOf())
        while (repository.indexNext()) Unit
        assertEquals("Long document", repository.search("receipt complete").single().title)
    }
    @Test fun backupRestoresAllContentAndSettingsOnlyAfterCommit() = runBlocking {
        val original = repository.import(Note(title = "Original"), source("attachment.txt", "Keep this".toByteArray()))
        val second = Note(title = "Second"); repository.save(second)
        repository.reorderVisible(listOf(original.id, second.id))
        val (encrypted, fingerprint) = backup.create({})!!
        sources += encrypted
        assertNull(backup.create({}, unchangedSince = fingerprint))
        val uri = source("backup.ssnb", encrypted.readBytes())
        repository.delete(original.id); repository.delete(second.id); repository.save(Note(title = "Current"))
        assertEquals(2, backup.stage(uri, phrase.uppercase().toCharArray()) {})
        assertEquals("Current", repository.notes.value.single().title)
        backup.commit()
        assertEquals(listOf(original, second), repository.notes.value)
        assertEquals(1, repository.attachments().size)
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(original.id, second.id), repository.notes.value.map(Note::id))
        assertEquals(phrase, repository.passphrase())
    }
    @Test fun recoveryPassphraseOpensTheSameVaultWithoutTheDeviceKey() = runBlocking {
        val note = Note(title = "Recoverable"); repository.save(note)
        assertEquals(false, repository.passphraseConfirmed.value)
        repository.confirmPassphrase(); assertEquals(true, repository.passphraseConfirmed.value)
        repository.close()
        assertThrows(Exception::class.java) { repository.recoverRoot(otherPhrase.toCharArray()) }
        val recovered = repository.recoverRoot(phrase.toCharArray())
        assertArrayEquals(root, recovered)
        repository.open(recovered)
        assertEquals(note, repository.notes.value.single())
        assertTrue(repository.ensurePassphrase { error("Existing passphrase must be kept") })
    }
    @Test fun restoreAdoptsTheBackupPassphraseForRecovery(): Unit = runBlocking {
        val note = Note(title = "From old phone"); repository.save(note)
        val (encrypted, _) = backup.create({})!!; sources += encrypted
        repository.access { repository.setPassphrase(it, otherPhrase, confirmed = false) }
        assertEquals(false, repository.passphraseConfirmed.value)
        try { backup.stage(source("old.ssnb", encrypted.readBytes()), otherPhrase.toCharArray()) {}; fail("Accepted another passphrase") } catch (_: Exception) { }
        backup.stage(source("old.ssnb", encrypted.readBytes()), phrase.toCharArray()) {}
        backup.commit()
        assertEquals(phrase, repository.passphrase()); assertEquals(true, repository.passphraseConfirmed.value)
        repository.close()
        assertArrayEquals(root, repository.recoverRoot(phrase.toCharArray()))
        assertThrows(Exception::class.java) { repository.recoverRoot(otherPhrase.toCharArray()) }
    }
    @Test fun manualOrderPersistsAndEditingDoesNotMoveNotes() = runBlocking {
        val first = Note(title = "First"); val second = Note(title = "Second"); val third = Note(title = "Third")
        listOf(first, second, third).forEach { repository.save(it) }
        assertEquals(listOf(third, second, first), repository.notes.value)
        repository.reorderVisible(listOf(second.id, first.id, third.id))
        repository.save(third.copy(title = "Edited", updatedAt = third.updatedAt + 1))
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(second.id, first.id, third.id), repository.notes.value.map(Note::id))
        repository.delete(first.id)
        assertEquals(listOf(second.id, third.id), repository.notes.value.map(Note::id))
        assertEquals(listOf(second.id, third.id), repository.access { it.noteOrder() })
    }
    @Test fun existingVaultConvertsLegacySortExactlyOnce() = runBlocking {
        val z = Note(title = "Zebra"); val a = Note(title = "Apple")
        repository.save(z); repository.save(a)
        repository.access { it.setSort(SortOrder.ALPHABETICAL); it.dao.deleteSecret("note-order") }
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(a, z), repository.notes.value)
        repository.save(z.copy(title = "Aardvark", updatedAt = z.updatedAt + 1))
        repository.close(); repository.open(root.copyOf())
        assertEquals(listOf(a.id, z.id), repository.notes.value.map(Note::id))
    }
    @Test fun versionOneBackupRestoresItsOriginalSortAsManualOrder() = runBlocking {
        val z = Note(title = "Zebra"); val a = Note(title = "Apple")
        val manifest = dev.securenotes.backup.BackupManifest(version = 1, notes = listOf(z, a), attachments = emptyList(), sort = SortOrder.ALPHABETICAL)
        val bytes = java.io.ByteArrayOutputStream()
        dev.securenotes.backup.BackupCodec.encrypt(bytes, "legacy password".toCharArray()) { clear ->
            val zip = java.util.zip.ZipOutputStream(clear)
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(documentJson.encodeToString(dev.securenotes.backup.BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry(); zip.finish(); zip.flush()
        }
        assertEquals(2, backup.stage(source("legacy.ssnb", bytes.toByteArray()), "legacy password".toCharArray()) {})
        backup.commit()
        assertEquals(listOf(a, z), repository.notes.value)
    }
    /** A restore folder backed by app files, standing in for the folder picker's tree. */
    private class FileFolder(private val root: File) : AttachmentFolder {
        override fun list(directory: AttachmentFolder.Entry?) =
            (if (directory == null) root else File(directory.id)).listFiles()!!.map { AttachmentFolder.Entry(it.absolutePath, it.name, it.isDirectory) }
        override fun open(entry: AttachmentFolder.Entry) = File(entry.id).inputStream()
    }
    /** Writes a version-4 backup set as a pair does: one file per attachment, then the index. Returns the index and its folder. */
    private suspend fun incrementalBackup(): Pair<Uri, File> {
        repository.confirmPassphrase()
        val snapshot = backup.capture {}
        val key = Crypto.newStreamingKey()
        val root = File(directory, "set-${newId()}"); val folder = File(root, "Notes-attachments").apply { mkdirs() }
        val names = snapshot.attachments.associate { a ->
            backup.prepareAttachment(snapshot, a, key).use { it.file.copyTo(File(folder, "${a.id}.ssna")) }
            a.id to "${a.id}.ssna"
        }
        val index = backup.prepareIndex(snapshot, key, folder.name.takeIf { names.isNotEmpty() }, names).use { source("index.ssnb", it.file.readBytes()) }
        return index to root
    }
    private suspend fun awaitFolder(index: Uri) {
        try { backup.stage(index, phrase.toCharArray()) {}; fail("Staged without attachment files") }
        catch (e: AttachmentFolderNeeded) { assertEquals("Notes-attachments", e.folder) }
        assertTrue(backup.awaitingFolder); assertNull(backup.pending)
    }
    @Test fun incrementalBackupRestoresThroughItsAttachmentFolder() = runBlocking {
        val first = repository.import(Note(title = "With files"), source("a.txt", "first".toByteArray()))
        repository.import(first, source("b.bin", ByteArray(3 * 1024 * 1024 + 17) { (it % 253).toByte() }))
        repository.save(Note(title = "Text only"))
        val expected = repository.notes.value; val attachments = repository.attachments().values
        val (index, root) = incrementalBackup()
        expected.forEach { repository.delete(it.id) }; repository.save(Note(title = "Current"))
        awaitFolder(index)
        // A folder without the files leaves current notes untouched and the authenticated index waiting.
        try { backup.stageFolder(FileFolder(File(root, "empty").apply { mkdirs() })) {}; fail("Accepted a folder without attachments") } catch (_: java.io.FileNotFoundException) { }
        assertEquals("Current", repository.notes.value.single().title); assertTrue(backup.awaitingFolder); assertNull(backup.pending)
        // The folder holding the attachment directory works, as does that directory itself after being renamed.
        assertEquals(2, backup.stageFolder(FileFolder(root)) {})
        backup.discard(); awaitFolder(index)
        val renamed = File(root, "moved").also { assertTrue(File(root, "Notes-attachments").renameTo(it)) }
        assertEquals(2, backup.stageFolder(FileFolder(renamed)) {})
        assertFalse(backup.awaitingFolder)
        backup.commit()
        assertEquals(expected, repository.notes.value)
        assertEquals(attachments.map { it.copy(indexed = false, nextPage = 0) }.toSet(), repository.attachments().values.map { it.copy(indexed = false, nextPage = 0) }.toSet())
        repository.access { v -> attachments.forEach { a -> v.read(a.id).use { assertEquals(a.sha256, dev.securenotes.backup.ciphertextDigest(it)) } } }
        assertEquals(phrase, repository.passphrase())
    }
    @Test fun incrementalRestoreRejectsMissingTruncatedSwappedAndAmbiguousFiles() = runBlocking {
        val note = repository.import(repository.import(Note(title = "Keep"), source("a.txt", "first".toByteArray())), source("b.txt", "second".toByteArray()))
        val (index, root) = incrementalBackup()
        val pristine = File(root, "Notes-attachments")
        val ids = repository.attachments().keys.toList()
        fun file(folder: File, id: String) = File(folder, "$id.ssna")
        val cases = mapOf<String, (File) -> Unit>(
            "missing" to { file(it, ids[0]).delete() },
            "truncated" to { f -> file(f, ids[0]).let { it.writeBytes(it.readBytes().copyOf(it.length().toInt() - 1)) } },
            "swapped" to { f -> val a = file(f, ids[0]).readBytes(); file(f, ids[0]).writeBytes(file(f, ids[1]).readBytes()); file(f, ids[1]).writeBytes(a) },
            "ambiguous" to { f -> val a = file(f, ids[0]); a.copyTo(File(f, "${ids[0]} (1).ssna")); a.renameTo(File(f, "${ids[0]} (2).ssna")) },
        )
        for ((name, damage) in cases) {
            val copy = File(directory, "case-${newId()}").also { pristine.copyRecursively(File(it, pristine.name)) }
            damage(File(copy, pristine.name))
            awaitFolder(index)
            try { backup.stageFolder(FileFolder(copy)) {}; fail("Accepted $name attachment files") } catch (_: Exception) { }
            assertEquals(name, note, repository.notes.value.single()); assertNull(name, backup.pending); assertTrue(name, backup.awaitingFolder)
            backup.discard()
        }
        // A provider collision suffix alone is fine: authentication, not the name, establishes identity.
        val suffixed = File(directory, "suffixed-${newId()}").also { pristine.copyRecursively(it) }
        assertTrue(file(suffixed, ids[0]).renameTo(File(suffixed, "${ids[0]} (1).ssna")))
        awaitFolder(index); assertEquals(1, backup.stageFolder(FileFolder(suffixed)) {}); backup.discard()
    }
    @Test fun versionFourIndexesRejectExtraEntriesAndInvalidKeysAndNeedNoFolderWithoutAttachments() = runBlocking {
        val note = Note(title = "Text"); repository.save(note)
        fun index(key: String, extra: Boolean): Uri {
            val manifest = BackupManifest(version = 4, notes = listOf(note), attachments = emptyList(), order = listOf(note.id), attachmentKey = key, attachmentFiles = emptyMap())
            val bytes = java.io.ByteArrayOutputStream()
            BackupCodec.encrypt(bytes, phrase.toCharArray()) { clear ->
                val zip = java.util.zip.ZipOutputStream(clear)
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write(documentJson.encodeToString(BackupManifest.serializer(), manifest).toByteArray()); zip.closeEntry()
                if (extra) { zip.putNextEntry(java.util.zip.ZipEntry("attachments/${newId()}")); zip.write(1); zip.closeEntry() }
                zip.finish(); zip.flush()
            }
            return source("v4.ssnb", bytes.toByteArray())
        }
        val key = BackupCodec.encodeKey(Crypto.newStreamingKey())
        repository.delete(note.id); repository.save(Note(title = "Current"))
        for (invalid in listOf(index(key, extra = true), index(BackupCodec.encodeKey(byteArrayOf(1, 2, 3)), extra = false))) {
            try { backup.stage(invalid, phrase.toCharArray()) {}; fail("Accepted invalid index") } catch (e: Exception) { assertFalse(e is AttachmentFolderNeeded) }
            assertEquals("Current", repository.notes.value.single().title); assertNull(backup.pending); assertFalse(backup.awaitingFolder)
        }
        assertEquals(1, backup.stage(index(key, extra = false), phrase.toCharArray()) {}); backup.commit()
        assertEquals(note, repository.notes.value.single())
    }
    private suspend fun fails(block: suspend () -> Unit) { try { block(); fail("Expected the check to fail") } catch (_: Exception) { } }
    @Test fun fullChecksAuthenticateIndexesAndAttachmentFilesWithoutStaging() = runBlocking {
        val note = repository.import(Note(title = "Checked"), source("a.txt", "first".toByteArray()))
        val (index, root) = incrementalBackup()
        val checked = context.contentResolver.openInputStream(index)!!.use { backup.checkIndex(it) }
        val attachment = checked.attachments.single()
        val key = checkNotNull(checked.key)
        val file = File(File(root, "Notes-attachments"), checked.files!!.getValue(attachment.id))
        file.inputStream().use { backup.checkAttachment(it, key, attachment) }
        // A changed byte, a truncation or another identity all fail.
        val bytes = file.readBytes()
        fails { backup.checkAttachment(bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }.inputStream(), key, attachment) }
        fails { backup.checkAttachment(bytes.copyOf(bytes.size - 1).inputStream(), key, attachment) }
        fails { backup.checkAttachment(bytes.inputStream(), key, attachment.copy(sha256 = "0".repeat(64))) }
        fails { backup.checkAttachment(bytes.inputStream(), key, attachment.copy(id = newId())) }
        // Whole files are checked with the attachments they contain.
        val whole = backup.create({})!!.first; sources += whole
        val wholeCheck = whole.inputStream().use { backup.checkIndex(it) }
        assertEquals(listOf(attachment), wholeCheck.attachments); assertNull(wholeCheck.files); assertNull(wholeCheck.key)
        fails { backup.checkIndex(whole.readBytes().also { it[it.size - 40] = (it[it.size - 40] + 1).toByte() }.inputStream()) }
        // Nothing was staged or replaced.
        assertNull(backup.pending); assertFalse(backup.awaitingFolder); assertEquals(note, repository.notes.value.single())
        key.fill(0)
    }
    @Test fun restorePublicationCarriesThePairKeyAndAttachmentRecordsOnly() = runBlocking {
        repository.save(Note(title = "Restored"))
        val file = backup.create({})!!.first; sources += file
        val tag = newId(); val other = newId(); val unfinished = newId()
        val config = BackupConfiguration(mode = BackupMode.PAIR, folder = "content://synthetic/folder", slots = listOf("A", "B").map {
            BackupSlot("content://synthetic/$it", "$it.ssnb", 123, "a".repeat(64))
        }, active = 0, phraseHash = BackupService.phraseHash(phrase), fingerprint = "b".repeat(64), savedAt = 123,
            pairTag = tag, attachmentFolder = BackupFolder("content://synthetic/folder/files", "files"),
            setup = PendingSetup("content://synthetic/new", unfinished, "20261019T000000000Z", BackupService.phraseHash(phrase), ownsGrant = true))
        repository.access { VaultBackupSettings.write(it, config) }
        val settings = VaultBackupSettings(repository)
        val record = AttachmentFile(newId(), 5, "c".repeat(64), "content://synthetic/folder/files/1", "1.ssna", 70, "d".repeat(64), complete = true)
        settings.saveKey(tag, byteArrayOf(1, 2, 3)) {}; settings.saveFile(tag, record) {}
        settings.saveKey(other, byteArrayOf(4)) {}; settings.saveFile(other, record) {}
        settings.saveKey(unfinished, byteArrayOf(5)) {}; settings.saveFile(unfinished, record.copy(complete = false)) {}
        backup.stage(source("pair.ssnb", file.readBytes()), phrase.toCharArray()) {}
        backup.commit()
        val restored = settings.load()
        assertEquals(tag, restored.pairTag); assertEquals(config.attachmentFolder, restored.attachmentFolder); assertTrue(restored.pending)
        assertEquals(config.setup, restored.setup)
        assertArrayEquals(byteArrayOf(1, 2, 3), settings.key(tag)); assertEquals(mapOf(record.id to record), settings.files(tag))
        // The unfinished setup's key and records move with it; unrelated rows do not.
        assertArrayEquals(byteArrayOf(5), settings.key(unfinished)); assertEquals(mapOf(record.id to record.copy(complete = false)), settings.files(unfinished))
        assertNull(settings.key(other)); assertTrue(settings.files(other).isEmpty())
        settings.forget(tag)
        assertNull(settings.key(tag)); assertTrue(settings.files(tag).isEmpty())
    }
    @Test fun corruptRestoreDoesNotReplaceExistingNotesAndCancelledStageIsDiscarded() = runBlocking {
        val note = Note(title = "Preserve me"); repository.save(note)
        val file = backup.create({})!!.first; sources += file
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try { backup.stage(source("corrupt.ssnb", bytes), phrase.toCharArray()) {}; fail("Accepted corrupt backup") } catch (_: Exception) { }
        assertEquals(note, repository.notes.value.single()); assertNull(backup.pending)
        backup.stage(source("valid.ssnb", file.readBytes()), phrase.toCharArray()) {}
        backup.discard(); assertEquals(note, repository.notes.value.single())
    }
    @Test fun interruptedStagingAndOrphanFilesAreCleanedOnReopen() = runBlocking {
        val note = Note(title = "Safe"); repository.save(note)
        val staging = repository.staging(); val stagedPath = staging.directory; staging.close()
        val partial = repository.vault!!.blobs.resolve("${newId()}.part"); partial.writeBytes(byteArrayOf(1, 2))
        repository.close(); repository.open(root.copyOf())
        assertEquals(note.id, repository.notes.value.single().id)
        assertFalse(stagedPath.exists()); assertFalse(partial.exists())
    }
    @Test fun interruptedImportJobResumesExactlyOnce() = runBlocking {
        val note = Note(title = "Pending import"); repository.save(note)
        val uri = source("pending.txt", "Resumed searchable content".toByteArray())
        val id = newId()
        repository.access { it.dao.putImport(dev.securenotes.storage.ImportRow(id, note.id, uri.toString(), "pending.txt", "text/plain")) }
        repository.close(); repository.open(root.copyOf())
        while (repository.indexNext()) { }
        assertEquals(1, repository.notes.value.single().document.attachments.size)
        assertEquals(id, repository.attachments().values.single().id)
        assertEquals(note.id, repository.search("resumed").single().noteId)
        assertFalse(repository.indexNext())
    }
    @Test fun seekableAttachmentReadsAcrossEncryptionSegmentsAndRejectsRevokedAccess() = runBlocking {
        val bytes = ByteArray(2_100_123) { (it % 239).toByte() }
        repository.import(Note(title = "Large file"), source("large.bin", bytes))
        val attachment = repository.attachments().values.single()
        var permitted = true
        repository.access { v ->
            val descriptor = v.descriptor(attachment.id, attachment.size) { check(permitted) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = java.nio.ByteBuffer.allocate(100)
                input.channel.position(1_048_550L); input.channel.read(buffer)
                assertArrayEquals(bytes.copyOfRange(1_048_550, 1_048_650), buffer.array())
                permitted = false
                try { input.channel.position(2_000_000L); input.read(); fail("Read after revocation") } catch (_: java.io.IOException) { }
            }
        }
    }
}

/** DocumentsContract's method name for deleteDocument. */
private const val DELETE_DOCUMENT = "android:deleteDocument"

/**
 * A save destination as Android permits providers to behave: it may reject "wt", keep trailing bytes on "w",
 * and report an unknown size as null. It records the methods called on it, such as [DELETE_DOCUMENT].
 */
private class SaveDestination(context: Context, val file: File, private val size: Long?, private val truncates: Boolean) : ContentProvider() {
    val calls = mutableListOf<String>()
    val uri: Uri = Uri.parse("content://dev.securenotes.test.saves/document")
    init { attachInfo(context, ProviderInfo().apply { authority = uri.authority }) }
    val resolver: ContentResolver = ContentResolver.wrap(this)
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(size)) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode == "wt" && !truncates) throw java.io.FileNotFoundException("Unsupported mode $mode")
        val truncate = if (mode == "wt") ParcelFileDescriptor.MODE_TRUNCATE else 0
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or truncate)
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? { calls += method; return null }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
