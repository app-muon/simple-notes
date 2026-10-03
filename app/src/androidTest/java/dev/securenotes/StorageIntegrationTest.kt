package dev.securenotes

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.securenotes.backup.BackupService
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.storage.NotesRepository
import kotlinx.coroutines.*
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
    @Before fun setup() = runBlocking {
        System.loadLibrary("sqlcipher")
        directory = File(context.cacheDir, "test-${newId()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = directory }
        repository = NotesRepository(isolated) { if (!authorized) throw CancellationException("Locked") }
        repository.open(root.copyOf())
        repository.ensurePassphrase { phrase }
        backup = BackupService(context, repository) {}
    }
    private val phrase = "cherisher driven greedily motion pyramid skipping unbundle vertigo"
    private val otherPhrase = "vertigo unbundle skipping pyramid motion greedily driven cherisher"
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
        repository.reorder(listOf(original.id, second.id))
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
        repository.reorder(listOf(second.id, first.id, third.id))
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
