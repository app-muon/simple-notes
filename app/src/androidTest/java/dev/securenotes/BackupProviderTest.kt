package dev.securenotes

import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.securenotes.backup.*
import dev.securenotes.document.Note
import dev.securenotes.document.newId
import dev.securenotes.security.Crypto
import dev.securenotes.storage.NotesRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FilterOutputStream

/** Real Android Downloads/storage provider, using a unique disposable subfolder and the real picker. */
class BackupProviderTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun unsupportedSyncAndProviderDiagnosticsAreBounded() {
        for (errno in listOf(android.system.OsConstants.EINVAL, android.system.OsConstants.ENOTSUP, android.system.OsConstants.ESPIPE)) assertTrue(unsupportedBackupSync(errno))
        for (errno in listOf(android.system.OsConstants.EIO, android.system.OsConstants.ENOSPC, android.system.OsConstants.EBADF)) assertFalse(unsupportedBackupSync(errno))
        val files = AndroidBackupFiles(context)
        val error = java.io.IOException("private/path", android.system.ErrnoException("secret name", android.system.OsConstants.EIO))
        assertEquals("IOException / ErrnoException EIO", files.diagnostic(error))
        var deep: Throwable = error
        repeat(10) { deep = java.io.IOException("private", deep) }
        assertEquals(5, files.diagnostic(deep).split(" / ").size)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
    }
    private fun clickSystem(label: String) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.equals(label, ignoreCase = true) == true) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null && (node.performAction(AccessibilityNodeInfo.ACTION_CLICK) || node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)) return
            Thread.sleep(100)
        }
        fail("Missing system picker action: $label")
    }
    @Test fun downloadsPairVerifiesAlternatesAndBothFilesRestoreIndependently() = runBlocking {
        val id = newId()
        val relative = "Download/NotesABTest-$id"
        shell("mkdir -p /sdcard/$relative")
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
        val selected = java.util.concurrent.atomic.AtomicReference<Uri?>()
        compose.setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selected.set(it) }
            Button(onClick = { picker.launch(initial) }) { Text("Choose test folder") }
        }
        compose.onNodeWithText("Choose test folder").performClick()
        clickSystem("Use this folder"); clickSystem("Allow")
        compose.waitUntil(15_000) { selected.get() != null }
        val tree = checkNotNull(selected.get()).toString()
        val directory = File(context.cacheDir, "provider-test-$id").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val repository = NotesRepository(isolated) {}
        val files = AndroidBackupFiles(context)
        var backup: BackupService? = null
        try {
            System.loadLibrary("sqlcipher")
            val testRoot = Crypto.random(32)
            repository.open(testRoot)
            val phrase = "cherisher driven greedily motion pyramid skipping unbundle vertigo"
            repository.ensurePassphrase { phrase }; repository.confirmPassphrase()
            val photo = ByteArray(2 * 1024 * 1024 + 99) { (it % 241).toByte() }
            val photoSource = File(File(context.cacheDir, "camera").apply { mkdirs() }, "$id-photo.jpg").apply { writeBytes(photo) }
            repository.import(Note(title = "Provider original"), androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.camera", photoSource))
            photoSource.delete()
            val source = BackupService(context, repository) {}
            backup = source
            val settings = VaultBackupSettings(repository)
            val coordinator = AutoBackup(files, settings, source, { true })
            source.coordinator = coordinator
            coordinator.refresh()
            val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var holds = 0
            val scheduler = BackupScheduler(schedulingScope, coordinator, repository.changes, { true }, repository::hasPendingEdits,
                repository::flush, { holds++; val release = (context.applicationContext as NotesApplication).keepAlive(); { release(); holds-- } }, { fail("Initial flush failed") })
            try {
                withContext(Dispatchers.Main) {
                    scheduler.onForeground()
                    val token = scheduler.beginSetup().token!!
                    scheduler.onLeaving()
                    assertTrue(scheduler.submitSetup(token, tree).admission.accepted)
                    assertTrue(scheduler.state.value.setupQueued); assertEquals(0, holds)
                    scheduler.onForeground(); assertEquals(1, holds)
                }
                withTimeout(30_000) { scheduler.state.first { !it.mutationsBlocked } }
                assertEquals(0, holds)
            } finally { schedulingScope.cancel() }
            val pair = settings.load()
            assertEquals(1, pair.active); assertEquals(2, pair.slots.size)
            assertTrue(files.hasGrant(tree)); assertNotEquals(pair.slots[0].uri, pair.slots[1].uri)
            // The attachment is one file in a confirmed folder beside the pair, recorded with its exact ciphertext.
            val folder = checkNotNull(pair.attachmentFolder)
            val saved = settings.files(checkNotNull(pair.pairTag)).values.single()
            assertTrue(saved.complete)
            assertEquals(listOf(saved.uri to saved.length), files.children(folder.uri).map { it.uri to it.size })
            assertEquals(saved.digest, files.openRead(saved.uri).use(::ciphertextDigest))
            suspend fun restore(index: String): Int = try { source.stage(Uri.parse(index), phrase.toCharArray()) {} }
                catch (e: AttachmentFolderNeeded) { assertEquals(folder.name, e.folder); source.stageFolder(DocumentTreeFolder(context.contentResolver, Uri.parse(tree))) {} }
            for (slot in pair.slots) {
                assertEquals(slot.sha256, files.openRead(slot.uri).use(::ciphertextDigest))
                repository.notes.value.forEach { repository.delete(it.id) }
                repository.save(Note(title = "Before restore"))
                assertEquals(1, restore(slot.uri))
                source.commit(); assertEquals("Provider original", repository.notes.value.single().title)
                val attachment = repository.attachments().values.single()
                repository.access { v -> v.read(attachment.id).use { assertArrayEquals(photo, it.readBytes()) } }
            }
            repository.save(repository.notes.value.single().copy(title = "Provider updated", updatedAt = System.currentTimeMillis() + 1))
            coordinator.run()
            val updated = settings.load()
            assertEquals(0, updated.active); assertFalse(updated.pending); assertNull(updated.error)
            assertEquals(pair.slots[1], updated.slots[1])
            // A text edit leaves the attachment file as it was.
            assertEquals(listOf(saved.uri to saved.length), files.children(folder.uri).map { it.uri to it.size })
            assertEquals(saved.digest, files.openRead(saved.uri).use(::ciphertextDigest))
            // A full check reads both indexes and the attachment through the provider.
            coordinator.verify()
            val checked = settings.load()
            assertTrue(checked.checkIsCurrent); assertEquals(listOf(SlotCheck.VERIFIED, SlotCheck.VERIFIED), checked.check?.slots)
            assertNull(checked.error); assertNull(coordinator.status.value?.checkError)
            restore(updated.slots[0].uri); source.commit()
            assertEquals("Provider updated", repository.notes.value.single().title)
            // Re-selecting the folder creates a new pair and never deletes the historical pair.
            assertTrue(coordinator.choose(tree).accepted)
            assertTrue(settings.load().managedUris.intersect(pair.managedUris).isEmpty())
            pair.slots.forEach { files.openRead(it.uri).close() }
            files.openRead(saved.uri).close()
            val newPair = settings.load()
            if (InstrumentationRegistry.getArguments().getString("backupCheckpoint") == "interrupt") {
                // Synthetic fixture only. The host kills this instrumentation process mid-copy and
                // reboots the disposable emulator, then invokes the resume test below.
                File(directory, "test-root").writeBytes(testRoot)
                File(directory, "test-tree").writeText(tree)
                val interrupted = object : BackupFiles by files {
                    override fun openWrite(uri: String, mode: String): BackupOutput {
                        val output = files.openWrite(uri, mode)
                        return object : BackupOutput by output {
                            override val stream = object : FilterOutputStream(output.stream) {
                                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                    output.stream.write(bytes, offset, minOf(length, 32)); output.stream.flush()
                                    File(context.cacheDir, "ab-interrupt-ready").writeText(id)
                                    while (true) Thread.sleep(1_000)
                                }
                            }
                        }
                    }
                }
                AutoBackup(interrupted, settings, source, { true }).also { it.refresh(); it.run(force = true) }
                fail("Host did not interrupt the copy")
            }
            coordinator.turnOff()
            newPair.slots.forEach { slot ->
                // Disable releases the persisted grant; the picker session still has temporary access.
                context.contentResolver.openInputStream(Uri.parse(slot.uri))!!.close()
            }
            assertEquals(BackupMode.DISABLED, settings.load().mode)
        } finally {
            backup?.discard(); repository.close(); directory.deleteRecursively()
            runCatching { files.release(tree) }
            // Only the UUID test folder created above is removed, never any user-selected path.
            shell("rm -rf /sdcard/Download/NotesABTest-$id")
        }
    }
    @Test fun resumesInterruptedDownloadsPairAfterReboot() = runBlocking {
        val marker = File(context.cacheDir, "ab-interrupt-ready")
        org.junit.Assume.assumeTrue("Run with backupCheckpoint=interrupt, force-stop and reboot first", marker.exists())
        val id = marker.readText().also { require(dev.securenotes.document.validId(it)) }
        val directory = File(context.cacheDir, "provider-test-$id")
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val repository = NotesRepository(isolated) {}
        val files = AndroidBackupFiles(context)
        val tree = File(directory, "test-tree").readText()
        var source: BackupService? = null
        try {
            System.loadLibrary("sqlcipher"); repository.open(File(directory, "test-root").readBytes())
            val settings = VaultBackupSettings(repository)
            val before = settings.load()
            assertTrue(before.pending); assertTrue(files.hasGrant(tree))
            val active = before.slots[before.active]
            assertEquals(active.sha256, files.openRead(active.uri).use(::ciphertextDigest))
            assertNotEquals(before.slots[1 - before.active].sha256, files.openRead(before.slots[1 - before.active].uri).use(::ciphertextDigest))
            val backups = BackupService(context, repository) {}; source = backups
            val coordinator = AutoBackup(files, settings, backups, { true }); backups.coordinator = coordinator
            coordinator.refresh()
            val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var holds = 0
            val scheduler = BackupScheduler(retryScope, coordinator, repository.changes, { true }, repository::hasPendingEdits,
                repository::flush, { holds++; val release = (context.applicationContext as NotesApplication).keepAlive(); { release(); holds-- } }, { fail("Retry flush failed") })
            try {
                withContext(Dispatchers.Main) { scheduler.onForeground() }
                withTimeout(30_000) { coordinator.status.first { it?.configuration?.pending == false } }
                withTimeout(30_000) { scheduler.state.first { !it.mutationsBlocked } }
                assertEquals(0, holds)
            } finally { retryScope.cancel() }
            val repaired = settings.load()
            assertFalse(repaired.pending); assertNull(repaired.error); assertEquals(1 - before.active, repaired.active)
            val phrase = repository.passphrase().toCharArray()
            try {
                for (slot in repaired.slots) {
                    try { backups.stage(Uri.parse(slot.uri), phrase) {} }
                    catch (_: AttachmentFolderNeeded) { backups.stageFolder(DocumentTreeFolder(context.contentResolver, Uri.parse(tree))) {} }
                    backups.commit()
                    assertEquals("Provider updated", repository.notes.value.single().title)
                }
            } finally { phrase.fill('\u0000') }
        } finally {
            source?.discard(); repository.close(); runCatching { files.release(tree) }
            directory.deleteRecursively(); marker.delete(); shell("rm -rf /sdcard/Download/NotesABTest-$id")
        }
    }
}
