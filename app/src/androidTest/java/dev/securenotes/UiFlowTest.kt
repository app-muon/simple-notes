package dev.securenotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
    /** An unlocked session past first-run passphrase confirmation. */
    private fun unlock() { runBlocking { app.unlock(Crypto.random(32)); app.repository.confirmPassphrase() } }
    @Test fun firstRunRequiresThreePassphraseWordsBeforeNotesAppear() {
        runBlocking { app.unlock(Crypto.random(32)) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.waitUntil(10_000) { vm.setupWords != null }
        val words = vm.setupWords!!
        assertEquals(dev.securenotes.security.Passphrase.WORDS, words.size)
        compose.onNodeWithContentDescription("New note").assertDoesNotExist()
        compose.onNodeWithText("I've saved them").performClick()
        val asked = (1..words.size).filter { compose.onAllNodesWithText("Word $it").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(3, asked.size)
        compose.onNodeWithText("Confirm").assertIsNotEnabled()
        asked.forEach { compose.onNodeWithText("Word $it").performTextInput(words[it - 1].uppercase()) }
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { vm.setupWords == null }
        compose.onNodeWithContentDescription("New note").assertIsDisplayed()
        assertEquals(true, app.repository.passphraseConfirmed.value)
    }
    @Test fun cancelledNoteDragDoesNotPersistReordering() {
        unlock()
        val notes = List(3) { Note(title = "Cancel $it") }
        runBlocking { notes.reversed().forEach { app.repository.save(it) } }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        val start = compose.onNodeWithText("Cancel 0").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithText("Cancel 2").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveTo(end, delayMillis = 400); cancel() }
        assertEquals(notes, app.repository.notes.value)
    }
    @Test fun draggingNearListEdgeScrollsBeyondInitiallyVisibleRows() {
        val notes = List(20) { Note(title = "Scroll $it") }
        var saved: List<String>? = null
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(240.dp)) {
                    ReorderableNotesList(notes, androidx.compose.foundation.lazy.rememberLazyListState(), {}, { ids, done -> saved = ids; done() })
                }
            }
        }
        val start = compose.onNodeWithText("Scroll 0").fetchSemanticsNode().boundsInRoot.center
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val end = androidx.compose.ui.geometry.Offset(start.x, bounds.bottom - 8)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveTo(end, delayMillis = 400) }
        repeat(100) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(20) }
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { saved != null }
        assertTrue("Dragged row should move beyond the first screen", saved!!.indexOf(notes.first().id) >= 5)
    }
    @Test fun noteListDragAndAccessibilityReorderPersistWithoutOpeningNote() {
        unlock()
        val notes = List(4) { Note(title = "Note $it") }
        runBlocking { notes.reversed().forEach { app.repository.save(it) } }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        val start = compose.onNodeWithText("Note 0").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithText("Note 3").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveTo(end, delayMillis = 400); up() }
        compose.waitUntil(10_000) { app.repository.notes.value.last().id == notes[0].id }
        assertEquals(Screen.LIST, vm.screen)
        val actions = compose.onNodeWithTag("note-row-${notes[0].id}").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Move up" }.action()) }
        compose.waitUntil(10_000) { app.repository.notes.value[2].id == notes[0].id }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Sort order").assertDoesNotExist()
    }
    @Test fun graceResumesAfterRepeatedLocks() {
        // The disposable emulator has a test PIN, exercising the real resume Keystore key.
        unlock()
        runBlocking { app.repository.save(Note(title = "Resume me")) }
        compose.runOnUiThread { app.lock() }
        runBlocking { assertTrue(app.tryResume()) }
        assertEquals("Resume me", app.repository.notes.value.single().title)
        compose.runOnUiThread { app.lock() }
        val sameRoot = runBlocking { app.tryResume() }
        assertTrue(sameRoot)
    }
    @Test fun expiredResumeRequiresAuthenticationButDoesNotInterruptEditing() {
        runBlocking { app.unlock(Crypto.random(32), authenticatedAt = android.os.SystemClock.elapsedRealtime() - dev.securenotes.security.ResumeSession.WINDOW_MS) }
        assertTrue(app.unlocked.value)
        compose.runOnUiThread { app.lock() }
        runBlocking { assertFalse(app.tryResume()) }
        assertFalse(app.unlocked.value)
    }
    private val body = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(BodyEditText::class.java)
    private fun plain(text: String) = Document(text = text, lines = List(text.count { it == '\n' } + 1) { Line() })
    @Test fun oneBodyEditorContinuesListsSelectsEverythingAndShowsEditingHeader() {
        unlock()
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("New note").performClick()
        compose.onNodeWithText("Editing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done").assertIsDisplayed()
        compose.onNodeWithText("Title").performTextInput("List")
        androidx.test.espresso.Espresso.onView(body).perform(androidx.test.espresso.action.ViewActions.click(), androidx.test.espresso.action.ViewActions.typeText("Intro"))
        compose.onNodeWithContentDescription("Bullet list").performClick()
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        // Enter continues the bullet list. The new, empty last item is indented behind its bullet, with the cursor
        // before the editor-only end marker.
        androidx.test.espresso.Espresso.onView(body).perform(androidx.test.espresso.action.ViewActions.typeText("\n"))
        compose.waitUntil(5_000) { vm.note?.document?.text == "Intro\n" }
        compose.runOnUiThread {
            assertEquals(6, editor.selectionStart)
            assertTrue(editor.layout.getPrimaryHorizontal(editor.selectionStart) > 0f)
        }
        // Enter on the empty item ends the list.
        androidx.test.espresso.Espresso.onView(body).perform(androidx.test.espresso.action.ViewActions.typeText("Second\n\nAfter"))
        compose.waitUntil(5_000) { vm.note?.document?.text == "Intro\nSecond\nAfter" }
        assertEquals(listOf(LineType.BULLET, LineType.BULLET, LineType.PARAGRAPH), vm.note!!.document.lines.map { it.type })
        compose.runOnUiThread { editor.selectAll(); editor.toggleBold() }
        compose.waitUntil(5_000) { vm.note!!.document.bold == listOf(BoldSpan(0, "Intro\nSecond\nAfter".length)) }
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(10_000) { !vm.editing && vm.busy == null }
        compose.onNodeWithText("Note").assertIsDisplayed()
        compose.onNodeWithText("Second").assertIsDisplayed()
    }
    @Test fun sharedTextIntoNewNoteReplacesTheEmptyBody() {
        unlock()
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.runOnUiThread { vm.receive(SharedContent("Shared content", emptyList(), "text/plain")) }
        compose.onNodeWithText("New note").performClick()
        assertEquals("Shared content", vm.note!!.document.text)
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
    @Test fun lockAsPassphraseSetupFinishesCannotPublishAnUnlockedSession() {
        val key = Crypto.random(32)
        // This flow is published on the IO thread inside passphrase setup, before unlock returns to Main.
        // Block that publication until Main has revoked the session, making the race deterministic.
        val revoked = app.scope.launch(Dispatchers.Unconfined) {
            app.repository.passphraseConfirmed.first { it == false }
            runBlocking(Dispatchers.Main) { app.lock() }
        }
        try {
            runBlocking {
                withTimeout(20_000) {
                    try {
                        withContext(Dispatchers.Main) { app.unlock(key.copyOf()) }
                        fail("A revoked unlock must be cancelled")
                    } catch (_: CancellationException) { }
                    revoked.join()
                }
            }
            assertFalse(app.unlocked.value)
            assertNull(app.repository.vault)
            runBlocking { withContext(Dispatchers.Main) { app.unlock(key.copyOf()) } }
            assertTrue(app.unlocked.value)
        } finally { revoked.cancel(); key.fill(0) }
    }
    @Test fun searchScrollsToDeepMatchAndHighlightsItsCharacters() {
        unlock()
        val body = List(40) { "Paragraph $it\nSeveral lines of ordinary text\nMore ordinary text" }.joinToString("\n") + "\nDeep receipt match"
        runBlocking { app.repository.save(Note(title = "Long note", document = plain(body))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and attachments").performTextInput("receipt")
        compose.waitUntil(10_000) { vm.results.isNotEmpty() }
        compose.onNodeWithText("Long note").performClick()
        compose.onNodeWithText("Deep receipt match").assertIsDisplayed()
        val text = compose.onNodeWithText("Deep receipt match").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.start == 5 && it.end == 12 && it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        // Reading mode composes every line (one selection spans the note); the first one is scrolled out of view.
        compose.onNodeWithText("Paragraph 0").assertIsNotDisplayed()
    }
    @Test fun restoreRejectsInvalidHeaderBeforePassphraseAndRequiresDestructiveConfirmation() {
        unlock()
        runBlocking { app.repository.save(Note(title = "Backup note")) }
        val phrase = runBlocking { app.repository.passphrase() }
        val encrypted = runBlocking { vm.backups.create({})!!.first }
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
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Recovery passphrase").performTextInput(phrase)
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(40_000) { vm.restoreCount != null }
        compose.onNodeWithText("Replace all notes?").assertIsDisplayed()
        assertEquals("Current note", app.repository.notes.value.single().title)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Current note", app.repository.notes.value.single().title)
        assertNull(vm.backups.pending)
        source.delete(); bad.delete(); encrypted.delete()
    }
    @Test fun choosingAutomaticBackupUsesCreateDocumentAndCancellationLeavesItOff() {
        unlock()
        runBlocking { app.repository.save(Note(title = "To back up")) }
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
        compose.onNodeWithText("Choose backup file").performClick()
        compose.waitUntil(10_000) { launched != null }
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, launched!!.action)
        assertEquals("application/octet-stream", launched!!.type)
        compose.onNodeWithText("Choose backup file").assertIsDisplayed()
        assertNull(app.autoBackup.status.value?.location)
        assertTrue(File(app.cacheDir, "backups").listFiles().orEmpty().isEmpty())
    }
    @Test fun authenticationGateDoesNotRenderNotes() {
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Notes").assertIsDisplayed()
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
        val note = Note(title = "Shopping", document = Document(text = "Coffee", lines = listOf(Line(LineType.CHECKLIST))))
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
        compose.waitUntil(10_000) { app.repository.notes.value.single().document.lines.first().checked }
        assertFalse(vm.editing)
        assertEquals("Coffee\nShared receipt", app.repository.notes.value.single().document.text)
    }
    @Test fun attachmentsSectionReordersAndImageOpensViewer() {
        unlock()
        val directory = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = File(directory, "image-test.png")
        val bitmap = android.graphics.Bitmap.createBitmap(30, 30, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val image = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", file)
        val text = File(directory, "file-test.txt").apply { writeText("Attachment text") }
        val textUri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.camera", text)
        val note = runBlocking {
            val first = app.repository.import(Note(title = "Pictures"), image)
            app.repository.import(first, textUri).also { vm.reloadAttachments() }
        }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Pictures").performClick()
        compose.onNodeWithText("Attachments").assertIsDisplayed()
        compose.onNodeWithText("file-test.txt").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("image-test.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("image-test.png").performScrollTo().performClick()
        compose.waitUntil { vm.screen == Screen.IMAGE }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnUiThread { vm.enterEditing(0) }
        compose.onNodeWithContentDescription("Options for image-test.png").performScrollTo().performClick()
        compose.onNodeWithText("Move left").assertIsNotEnabled()
        compose.onNodeWithText("Move right").performClick()
        compose.waitUntil(10_000) { app.repository.notes.value.single().document.attachments == note.document.attachments.reversed() }
        file.delete(); text.delete()
    }
}
