package dev.securenotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UiFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<NotesApplication>()
    private lateinit var vm: NotesViewModel
    @Before fun setup() {
        // Test-only in-process session; production authentication has no bypass intent or preference.
        runBlocking { app.repository.close() }
        File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
        compose.runOnUiThread { vm = NotesViewModel(app) }
    }
    @After fun cleanup() {
        compose.runOnUiThread { app.lock() }
        runBlocking { app.repository.close() }
        File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
    }
    private fun unlock() { runBlocking { app.unlock(Crypto.random(32)) } }
    @Test fun dragCanMoveAttachmentAcrossSeveralVisibleBlocks() {
        unlock()
        val source = File(File(app.cacheDir, "camera").apply { mkdirs() }, "drag-file.txt").apply { writeText("Drag source") }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", source)
        val note = runBlocking {
            var n = Note(title = "Reorder")
            repeat(3) { n = app.repository.import(n, uri) }
            vm.reloadAttachments(); n
        }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Reorder").performClick()
        compose.runOnUiThread { vm.editing = true; vm.focusTitle = false; vm.focusedBlock = null }
        val files = compose.onAllNodesWithText("drag-file.txt")
        val start = files[0].fetchSemanticsNode().boundsInRoot.center
        val end = files[2].fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveTo(end, delayMillis = 400); up() }
        compose.waitUntil(10_000) { vm.note!!.document.blocks.last().id == note.document.blocks[1].id }
        source.delete()
    }
    @Test fun sharedTextIntoNewNoteReplacesTheEmptyDraftBlock() {
        unlock()
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.runOnUiThread { vm.receive(SharedContent("Shared content", emptyList(), "text/plain")) }
        compose.onNodeWithText("New note").performClick()
        assertEquals(listOf("Shared content"), vm.note!!.document.blocks.map { it.text })
    }
    @Test fun lockDuringOpeningCannotPublishAnUnlockedSession() {
        val key = Crypto.random(32)
        runBlocking { app.repository.mutex.lock() }
        lateinit var opening: Job
        compose.runOnUiThread {
            opening = app.scope.launch { try { app.unlock(key.copyOf()) } catch (_: CancellationException) { } }
            app.lock()
        }
        app.repository.mutex.unlock()
        runBlocking { withTimeout(10_000) { opening.join() } }
        assertFalse(app.unlocked.value)
        runBlocking { app.unlock(key.copyOf()) }
        assertTrue(app.unlocked.value)
    }
    @Test fun duplicateUnlocksKeepOneAuthorizedVault() {
        val key = Crypto.random(32)
        runBlocking { coroutineScope { repeat(4) { launch { app.unlock(key.copyOf()) } } } }
        val vault = app.repository.vault
        val discardedKey = Crypto.random(32)
        runBlocking { app.unlock(discardedKey) }
        assertSame(vault, app.repository.vault); assertTrue(app.unlocked.value)
        assertTrue(discardedKey.all { it == 0.toByte() })
    }
    @Test fun searchScrollsToDeepMatchAndHighlightsItsCharacters() {
        unlock()
        val blocks = List(40) { Block(text = "Paragraph $it\nSeveral lines of ordinary text\nMore ordinary text") } + Block(text = "Deep receipt match")
        runBlocking { app.repository.save(Note(title = "Long note", document = Document(blocks = blocks))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and attachments").performTextInput("receipt")
        compose.waitUntil(10_000) { vm.results.isNotEmpty() }
        compose.onNodeWithText("Long note").performClick()
        compose.onNodeWithText("Deep receipt match").assertIsDisplayed()
        val text = compose.onNodeWithText("Deep receipt match").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.start == 5 && it.end == 12 && it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        compose.onNodeWithText(blocks.first().text).assertDoesNotExist()
    }
    @Test fun restoreRejectsInvalidHeaderBeforePasswordAndRequiresDestructiveConfirmation() {
        unlock()
        runBlocking {
            app.repository.save(Note(title = "Backup note")); vm.backups.configure("test password".toCharArray())
        }
        val encrypted = runBlocking { vm.backups.create("test password".toCharArray()) {} }
        val source = File(File(app.cacheDir, "camera").apply { mkdirs() }, "restore-test.ssnb").apply { writeBytes(encrypted.readBytes()) }
        val bad = File(source.parentFile, "invalid-test.bin").apply { writeText("not a backup") }
        val validUri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", source)
        val badUri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", bad)
        runBlocking { app.repository.notes.value.forEach { app.repository.delete(it.id) }; app.repository.save(Note(title = "Current note")) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.runOnUiThread { vm.selectRestore(badUri) }
        compose.waitUntil(10_000) { vm.error != null }
        assertNull(vm.passwordMode)
        compose.onNodeWithText("OK").performClick()
        compose.runOnUiThread { vm.selectRestore(validUri) }
        compose.waitUntil(10_000) { vm.passwordMode == "restore" }
        compose.onNodeWithText("Password").performTextInput("test password")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(40_000) { vm.restoreCount != null }
        compose.onNodeWithText("Replace all notes?").assertIsDisplayed()
        assertEquals("Current note", app.repository.notes.value.single().title)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Current note", app.repository.notes.value.single().title)
        assertNull(vm.backups.pending)
        source.delete(); bad.delete(); encrypted.delete()
    }
    @Test fun backupUsesCreateDocumentAndCancellationCleansEncryptedTemporaryFile() {
        unlock()
        runBlocking { app.repository.save(Note(title = "To back up")); vm.backups.configure("test password".toCharArray()) }
        var launched: android.content.Intent? = null
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                launched = contract.createIntent(app, input)
                dispatchResult(requestCode, android.app.Activity.RESULT_CANCELED, null)
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) { SecureNotesApp(vm, {}, {}, {}) }
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Create encrypted backup").performClick()
        compose.waitUntil(10_000) { vm.passwordMode == "backup" }
        compose.onNodeWithText("Password").performTextInput("test password")
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(40_000) { launched != null }
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, launched!!.action)
        assertEquals("application/octet-stream", launched!!.type)
        compose.waitUntil(10_000) { vm.backupFile == null }
        assertTrue(File(app.cacheDir, "backups").listFiles().orEmpty().isEmpty())
    }
    @Test fun authenticationGateDoesNotRenderNotes() {
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Secure Notes").assertIsDisplayed()
        compose.onNodeWithContentDescription("New note").assertDoesNotExist()
    }
    @Test fun createAutosaveReadingBackSearchAndConfirmedDelete() {
        unlock()
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithText("Title").assertIsFocused()
        compose.onNodeWithText("Title").performTextInput("Travel café")
        // Exercise system Back while the title keyboard is open, not just the toolbar button.
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) { !vm.editing && vm.busy == null }
        compose.onNodeWithText("Travel café").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { vm.screen == Screen.LIST }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and attachments").performTextInput("cafe")
        compose.waitUntil(10_000) { vm.results.isNotEmpty() }
        compose.onNodeWithText("Travel café").performClick()
        compose.onNodeWithContentDescription("Note menu").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this note?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, app.repository.notes.value.size)
        compose.onNodeWithContentDescription("Note menu").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { vm.screen == Screen.LIST }
        assertTrue(app.repository.notes.value.isEmpty())
    }
    @Test fun shareRequiresChoiceAndChecklistCanToggleInReadingMode() {
        unlock()
        val note = Note(title = "Shopping", document = Document(blocks = listOf(Block(type = BlockType.CHECKLIST, text = "Coffee"))))
        runBlocking { app.repository.save(note) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.runOnUiThread { vm.receive(SharedContent("Shared receipt", emptyList(), "text/plain")) }
        compose.onNodeWithText("Add shared content").assertIsDisplayed()
        assertEquals(1, app.repository.notes.value.size)
        compose.onNodeWithText("Add to existing note").performClick()
        compose.onNodeWithText("Shopping").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { !vm.editing && vm.busy == null }
        compose.onNodeWithContentDescription("Coffee").performClick()
        compose.waitUntil(10_000) { app.repository.notes.value.single().document.blocks.first().checked }
        assertFalse(vm.editing)
        assertTrue(app.repository.notes.value.single().document.blocks.any { it.text == "Shared receipt" })
    }
    @Test fun imageFileBlocksCanBeReorderedAndImageOpensViewer() {
        unlock()
        val directory = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = File(directory, "image-test.png")
        val bitmap = android.graphics.Bitmap.createBitmap(30, 30, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val image = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", file)
        val text = File(directory, "file-test.txt").apply { writeText("Attachment text") }
        val textUri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", text)
        val note = runBlocking {
            val first = app.repository.import(Note(title = "Attachments"), image)
            app.repository.import(first, textUri).also { vm.reloadAttachments() }
        }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Attachments").performClick()
        compose.onNodeWithText("image-test.png").performScrollTo().performClick()
        compose.waitUntil { vm.screen == Screen.IMAGE }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnUiThread { vm.enterEditing(note.document.blocks.first().id) }
        compose.onAllNodesWithContentDescription("Attachment options")[0].performScrollTo().performClick()
        compose.onNodeWithText("Move down").performClick()
        compose.waitUntil(10_000) { app.repository.notes.value.single().document.blocks.last().attachmentId == note.document.blocks[1].attachmentId }
        file.delete(); text.delete()
    }
}
