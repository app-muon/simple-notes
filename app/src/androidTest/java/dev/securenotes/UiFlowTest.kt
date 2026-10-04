package dev.securenotes

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
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
        compose.runOnUiThread {
            // Match MainActivity: the generic Compose test activity otherwise pans its entire window for a
            // native EditText, moving the fixed toolbar and invalidating caret/viewport assertions.
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {
                    it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            vm = NotesViewModel(app)
        }
    }
    @After fun cleanup() {
        compose.runOnUiThread { app.lock() }
        runBlocking { app.repository.close() }
        File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
    }
    /** An unlocked session past first-run passphrase confirmation. */
    private fun unlock() { runBlocking { app.unlock(Crypto.random(32)); app.repository.confirmPassphrase() } }
    private fun capture(name: String) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        val dark = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val file = File(app.getExternalFilesDir(null), "$name-${if (dark) "dark" else "light"}.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun awaitFind(query: String, count: Int) {
        compose.waitUntil(10_000) { vm.find.query == query && !vm.find.pending && vm.find.matches.size == count }
    }
    @Test fun findKeepsFirstChecklistRowWholeAndReachesDeepWrappedMatch() {
        unlock()
        val wrapped = "Wrapped words ".repeat(140) + "target"
        val text = (List(30) { "Leading paragraph $it" } + "Checklist target" + wrapped + List(15) { "Trailing paragraph $it" }).joinToString("\n")
        val document = plain(text).let { it.copy(lines = it.lines.mapIndexed { i, line -> if (i in 30..31) Line(LineType.CHECKLIST) else line }) }
        runBlocking { app.repository.save(Note(title = "Find checklist", document = document)) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Find checklist").performClick()
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
        compose.waitForIdle()
        val checkbox = compose.onNodeWithContentDescription("Checklist target").fetchSemanticsNode()
        assertEquals("The first matched checkbox must be fully visible", checkbox.size.height.toFloat(), checkbox.boundsInRoot.height, 1f)
        assertTrue(checkbox.positionInRoot.y >= compose.onNodeWithTag("find-counter").fetchSemanticsNode().boundsInRoot.bottom)
        capture("find-checklist-first-row")
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.waitForIdle()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(wrapped).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val node = compose.onNodeWithText(wrapped).fetchSemanticsNode()
        val layout = layouts.single()
        val box = layout.getBoundingBox(wrapped.indexOf("target"))
        // Semantics includes the Text's symmetric vertical padding; TextLayoutResult does not.
        val textTop = node.positionInRoot.y + (node.size.height - layout.size.height) / 2f
        capture("find-checklist-wrapped-row")
        assertTrue("Deep match top ${textTop + box.top} must be inside ${node.boundsInRoot}", textTop + box.top >= node.boundsInRoot.top - 1f)
        assertTrue("Deep match bottom ${textTop + box.bottom} must be inside ${node.boundsInRoot}", textTop + box.bottom <= node.boundsInRoot.bottom + 1f)
        compose.onNodeWithTag("find-query").assertIsFocused()
    }
    @Test fun tagAssignmentAndTagHistoryKeepFindCursorDestination() {
        unlock()
        val tag = runBlocking { app.repository.createTag("Work") }
        val text = "target gap target"
        runBlocking { app.repository.save(Note(title = "Tag destination", document = plain(text))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Tag destination").performClick()
        compose.runOnUiThread { vm.enterEditing(1) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithContentDescription("Note menu").performClick()
        compose.onNodeWithText("Tags").performClick()
        compose.onNode(hasText("Work") and isToggleable()).performClick()
        compose.waitUntil { tag.id in vm.note!!.tagIds }
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        compose.waitUntil { compose.onAllNodesWithText("Create tag").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertTrue(editor.hasFocus()); assertEquals(text.lastIndexOf("target"), editor.selectionStart) }

        compose.runOnUiThread { editor.setSelection(1) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.waitUntil { vm.note!!.tagIds.isEmpty() }
        compose.onNodeWithContentDescription("Redo").performClick()
        compose.waitUntil { tag.id in vm.note!!.tagIds }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertEquals(text.lastIndexOf("target"), editor.selectionStart) }
    }
    @Test fun bodyTextAndFormattingHistoryTakePriorityOverFind() {
        unlock()
        val text = "target gap target"
        runBlocking { app.repository.save(Note(title = "History destination", document = plain(text))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("History destination").performClick()
        compose.runOnUiThread { vm.enterEditing(1) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.runOnUiThread { editor.text!!.append("!"); editor.setSelection(1) }
        for ((action, expected) in listOf("Undo" to text, "Redo" to "$text!")) {
            compose.onNodeWithContentDescription("Find in note").performClick()
            compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
            compose.onNodeWithContentDescription("Next match").performClick()
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitUntil { vm.note!!.document.text == expected }; awaitFind("target", 2)
            compose.onNodeWithContentDescription("Close find").performClick()
            compose.runOnIdle { assertTrue(editor.hasFocus()); assertEquals(1, editor.selectionStart) }
        }
        compose.runOnUiThread { editor.setSelection(1, 4); editor.toggleBold() }
        for ((action, expected) in listOf("Undo" to emptyList(), "Redo" to listOf(BoldSpan(1, 4)))) {
            compose.onNodeWithContentDescription("Find in note").performClick()
            compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
            compose.onNodeWithContentDescription("Next match").performClick()
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitUntil { vm.note!!.document.bold == expected }
            compose.onNodeWithContentDescription("Close find").performClick()
            compose.runOnIdle { assertEquals(1, editor.selectionStart); assertEquals(4, editor.selectionEnd) }
        }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.runOnUiThread { vm.history.clear(); vm.undo(); vm.redo() }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertEquals(text.lastIndexOf("target"), editor.selectionStart) }
    }
    @Test fun titleHistoryTakesPriorityOverBodyFindMatch() {
        unlock()
        runBlocking { app.repository.save(Note(title = "Original title", document = plain("target gap target"))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Original title").performClick()
        compose.onNodeWithText("Original title").performClick()
        compose.onNodeWithText("Original title").performTextReplacement("Edited title")
        compose.onNodeWithText("Edited title").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 4))
        for ((action, expected) in listOf("Undo" to "Original title", "Redo" to "Edited title")) {
            compose.onNodeWithContentDescription("Find in note").performClick()
            compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 2)
            compose.onNodeWithContentDescription("Next match").performClick()
            compose.onNodeWithContentDescription(action).performClick()
            compose.waitUntil { vm.note!!.title == expected }; awaitFind("target", 2)
            compose.onNodeWithContentDescription("Close find").performClick()
            compose.onNodeWithText(expected).assertIsFocused()
            compose.onNodeWithText(expected).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(2, 4)))
        }
    }
    @Test fun closeFindPreservesLatestBodyAndTitleSelectionsAndTyping() {
        unlock()
        runBlocking { app.repository.save(Note(title = "Cursor title", document = plain("café café body"))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Cursor title").performClick()
        compose.runOnUiThread { vm.enterEditing(2) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe"); awaitFind("cafe", 2)
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.runOnUiThread { editor.requestFocus(); editor.setSelection(10, 13) }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertTrue(editor.hasFocus()); assertEquals(10, editor.selectionStart); assertEquals(13, editor.selectionEnd) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe"); awaitFind("cafe", 2)
        compose.runOnUiThread { editor.requestFocus(); editor.setSelection(editor.document.text.length); editor.text!!.append(" typed") }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertEquals(editor.document.text.length, editor.selectionStart) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe"); awaitFind("cafe", 2)
        compose.onNodeWithText("Cursor title").performClick().performTextInputSelection(androidx.compose.ui.text.TextRange(2, 4))
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("Cursor title").assertIsFocused()
        compose.onNodeWithText("Cursor title").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(2, 4)))
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("absent"); awaitFind("absent", 0)
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("Cursor title").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange, androidx.compose.ui.text.TextRange(2, 4)))
    }
    @Test fun immediateCloseResolvesCurrentQueryAndLaterInputOrReopeningWins() {
        unlock()
        runBlocking { app.repository.save(Note(title = "Immediate", document = plain("first target last"))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Immediate").performClick()
        compose.runOnUiThread { vm.enterEditing(0) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.runOnUiThread { vm.findQuery("target"); assertTrue(vm.find.pending); vm.closeFind() }
        compose.waitUntil { editor.hasFocus() && editor.selectionStart == 6 }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.runOnUiThread {
            vm.findQuery("target"); vm.closeFind()
            editor.requestFocus(); editor.setSelection(1, 3)
        }
        compose.runOnIdle { assertEquals(1, editor.selectionStart); assertEquals(3, editor.selectionEnd) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.runOnUiThread { vm.findQuery("target"); vm.closeFind(); vm.openFind(); vm.findQuery("last") }
        awaitFind("last", 1)
        compose.onNodeWithTag("find-query").assertIsFocused()
        compose.runOnUiThread { vm.closeFind(); vm.open(Note(title = "Other")) }
        compose.waitForIdle(); assertEquals("Other", vm.note!!.title); assertNull(vm.findReturn)
        compose.runOnUiThread { vm.openFind(); vm.findQuery("Other"); vm.closeFind(); app.lock() }
        compose.waitUntil { !app.unlocked.value }; assertFalse(vm.findOpen); assertNull(vm.note)
    }
    @Test fun doneClosesFindAndEditingInOneTapWhileBackRemainsStaged() {
        unlock()
        runBlocking { app.repository.save(Note(title = "Finish", document = plain("target"))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Finish").performClick()
        compose.runOnUiThread { vm.enterEditing(0) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("target"); awaitFind("target", 1)
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitUntil(10_000) { !vm.editing && !vm.findOpen }
        assertEquals(Screen.NOTE, vm.screen)
        compose.onNodeWithText("Finish").performClick()
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil { !vm.findOpen }; assertTrue(vm.editing)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { !vm.editing }; assertEquals(Screen.NOTE, vm.screen)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil { vm.screen == Screen.LIST }
    }
    @Test fun failedDoneKeepsNoteEditableAndFindClosed() {
        unlock()
        val note = Note(title = "Save failure")
        runBlocking { app.repository.save(note) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText(note.title).performClick()
        compose.onNodeWithText(note.title).performClick()
        runBlocking { app.repository.access { it.database.openHelper.writableDatabase.execSQL("PRAGMA query_only=ON") } }
        try {
            compose.onNodeWithText(note.title).performTextReplacement("Pending title")
            compose.onNodeWithContentDescription("Find in note").performClick()
            compose.onNodeWithContentDescription("Done").performClick()
            compose.waitUntil(10_000) { vm.error != null }
            assertTrue(vm.editing); assertFalse(vm.findOpen); assertEquals(Screen.NOTE, vm.screen)
        } finally {
            runBlocking { app.repository.access { it.database.openHelper.writableDatabase.execSQL("PRAGMA query_only=OFF") }; vm.flush() }
        }
    }
    @Test fun tappingToEditCancelsFindDestinationAndTitleTrimmingKeepsOffsets() {
        unlock()
        val text = "First line\n" + "Ordinary words ".repeat(180) + "target"
        runBlocking { app.repository.save(Note(title = "  café 😀  ", document = plain(text))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("café 😀").performClick()
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe"); awaitFind("cafe", 1)
        val title = compose.onNodeWithText("café 😀").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue(title.spanStyles.any { it.start == 0 && it.end == 4 })
        compose.onNodeWithTag("find-query").performTextReplacement("  cafe"); awaitFind("  cafe", 1)
        val clippedTitle = compose.onNodeWithText("café 😀").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertTrue(clippedTitle.spanStyles.any { it.start == 0 && it.end == 4 })
        compose.onNodeWithTag("find-query").performTextReplacement("target"); awaitFind("target", 1)
        compose.onNodeWithText("First line").performScrollTo().performClick()
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.runOnIdle {
            assertEquals(10, editor.selectionStart)
            val location = IntArray(2); editor.getLocationOnScreen(location)
            assertTrue("Tapping the first line keeps its caret visible", location[1] > 0)
            assertNull(vm.findScrollTarget)
        }
        capture("find-tap-to-edit")
    }
    @Test fun globalSearchKeepsEntireChecklistRowVisible() {
        unlock()
        // Trailing rows let the target align with the top of the viewport: clamping at the end would hide a clipped-checkbox bug.
        val text = (List(45) { "Ordinary paragraph $it" } + "Checklist target" + List(15) { "Trailing paragraph $it" }).joinToString("\n")
        val document = plain(text).let { it.copy(lines = it.lines.mapIndexed { i, line -> if (i == 45) Line(LineType.CHECKLIST) else line }) }
        runBlocking { app.repository.save(Note(title = "Checklist search", document = document)) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and attachments").performTextInput("target")
        compose.waitUntil { vm.results.isNotEmpty() }
        compose.onNodeWithText("Checklist search").performClick()
        compose.waitForIdle()
        val checkbox = compose.onNodeWithContentDescription("Checklist target").fetchSemanticsNode()
        assertEquals(checkbox.size.height.toFloat(), checkbox.boundsInRoot.height, 1f)
        assertTrue(checkbox.positionInRoot.y > compose.onNodeWithText("Note").fetchSemanticsNode().boundsInRoot.bottom)
        capture("search-checklist-row")
    }
    @Test fun deletingCatalogTagReconcilesOpenNoteFilterAndAffectedUndo() {
        unlock()
        val tag = runBlocking { app.repository.createTag("Work") }
        val note = Note(title = "Tagged", tagIds = listOf(tag.id))
        runBlocking { app.repository.save(note) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.runOnUiThread { vm.selectFilter(NoteFilter(tagId = tag.id)); vm.open(note); vm.enterEditing(); vm.change(note.copy(title = "Edited")) }
        assertTrue(vm.history.canUndo)
        runBlocking { app.repository.deleteTag(tag.id) } // Exercise the catalog collector, not the VM delete wrapper.
        compose.waitUntil { vm.note!!.tagIds.isEmpty() && vm.filter == NoteFilter() && !vm.history.canUndo }
        compose.runOnUiThread { vm.undo() }; assertEquals("Edited", vm.note!!.title)
        compose.runOnUiThread { vm.change(vm.note!!.copy(title = "Another edit")) }
        val unrelated = runBlocking { app.repository.createTag("Unrelated") }
        runBlocking { app.repository.deleteTag(unrelated.id) }
        compose.waitForIdle(); assertTrue(vm.history.canUndo)
    }
    @Test fun findReadingWrapsHighlightsAndReachesDeepWrappedParagraph() {
        unlock()
        val text = "Ordinary words ".repeat(400) + "cafe\u0301 end"
        val title = "Title words ".repeat(60) + "CAFÉ"
        val note = Note(title = title, document = plain(text))
        runBlocking { app.repository.save(note) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe")
        compose.waitUntil(10_000) { vm.find.matches.size == 2 }
        compose.onNodeWithText("1 of 2").assertIsDisplayed()
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        val titleY = compose.onNodeWithText(title).fetchSemanticsNode().positionInRoot.y + titleLayouts.single().getBoundingBox(title.indexOf("CAFÉ")).top
        assertTrue("Wrapped title match must be visible: $titleY", titleY > 0 && titleY < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithContentDescription("Previous match").performClick()
        compose.onNodeWithText("2 of 2").assertIsDisplayed()
        compose.onNodeWithTag("find-query").assertIsFocused()
        compose.waitForIdle()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val matchY = node.positionInRoot.y + layouts.single().getBoundingBox(text.indexOf("cafe")).top
        assertTrue("Deep wrapped match must be visible: $matchY", matchY > 0 && matchY < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(layouts.single().layoutInput.text.spanStyles.any { it.start == text.indexOf("cafe") })
        capture("find-reading")
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithText("1 of 2").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil { !vm.findOpen }
        assertEquals(Screen.NOTE, vm.screen); assertEquals("", vm.find.query)
    }
    @Test fun findEditorKeepsFormattingHistoryFocusAndRestoresCursor() {
        unlock()
        val document = plain("café café").copy(bold = listOf(BoldSpan(0, 4)))
        runBlocking { app.repository.save(Note(title = "Editor", document = document)) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Editor").performClick()
        compose.runOnUiThread { vm.enterEditing(3) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe")
        compose.waitUntil(10_000) { vm.find.matches.size == 2 }
        assertFalse(vm.history.canUndo)
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithTag("find-query").assertIsFocused()
        compose.runOnUiThread {
            assertEquals(2, editor.text!!.getSpans(0, editor.length(), FindHighlightSpan::class.java).size)
            assertEquals(document, editor.document)
            editor.text!!.append(" café")
        }
        compose.waitUntil(10_000) { vm.find.matches.size == 3 }
        compose.runOnUiThread { vm.undo() }
        compose.waitUntil(10_000) { vm.find.matches.size == 2 }
        assertEquals(document, vm.note!!.document)
        compose.runOnUiThread { vm.redo() }
        compose.waitUntil(10_000) { vm.find.matches.size == 3 }
        compose.runOnUiThread { vm.findQuery("cafe") } // An explicit Find action makes the result the destination again.
        awaitFind("cafe", 3)
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.runOnIdle { assertTrue(editor.hasFocus()); assertEquals(5, editor.selectionStart); assertTrue(vm.editing) }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("missing")
        awaitFind("missing", 0)
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next match").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil { !vm.findOpen }
        compose.runOnIdle { assertEquals(5, editor.selectionStart); assertTrue(vm.editing) }
        runBlocking { vm.flush() }
        assertEquals(document.bold, app.repository.notes.value.single().document.bold)
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("editor")
        compose.waitUntil(10_000) { vm.find.matches.size == 1 }
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("Editor").assertIsFocused()
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("absent")
        awaitFind("absent", 0)
        compose.onNodeWithContentDescription("Close find").performClick()
        compose.onNodeWithText("Editor").assertIsFocused()
    }
    @Test fun findEditorScrollsToWrappedResultButDoesNotJumpAfterOrdinaryEdits() {
        unlock()
        val prefix = "Ordinary words ".repeat(180)
        val text = prefix + "café café"
        runBlocking { app.repository.save(Note(title = "Wrapped editor", document = plain(text))) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithText("Wrapped editor").performClick()
        compose.runOnUiThread { vm.enterEditing(0) }
        lateinit var editor: BodyEditText
        androidx.test.espresso.Espresso.onView(body).check { view, _ -> editor = view as BodyEditText }
        compose.onNodeWithContentDescription("Find in note").performClick()
        compose.onNodeWithTag("find-query").performTextInput("cafe")
        compose.waitUntil(10_000) { vm.find.matches.size == 2 }
        compose.waitForIdle()
        compose.runOnIdle {
            val location = IntArray(2); editor.getLocationOnScreen(location)
            val y = location[1] + editor.matchRect(prefix.length)!!.top
            assertTrue("Wrapped editor match must be visible: $y", y > 0 && y < editor.rootView.height)
            assertFalse(editor.hasFocus())
        }
        compose.onNodeWithTag("find-query").assertIsFocused()
        capture("find-editor")
        compose.runOnUiThread { editor.focusAt(0) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Body should take focus for ordinary editing", editor.hasFocus()) }
        compose.runOnUiThread { editor.text!!.insert(0, "Typed ") }
        compose.waitUntil(10_000) { vm.find.active?.start == prefix.length + 6 }
        capture("find-ordinary-edit")
        compose.runOnIdle {
            val location = IntArray(2); editor.getLocationOnScreen(location)
            assertTrue("Ordinary editing must keep the caret near the start visible: y=${location[1]}, scroll=${editor.scrollY}, selection=${editor.selectionStart}", location[1] > 0)
        }
    }
    @Test fun tagSheetsAssignmentsFilteringAndInheritedEmptyDraft() {
        unlock()
        val tag = runBlocking { app.repository.createTag("Work") }
        val tagged = Note(title = "Tagged needle", tagIds = listOf(tag.id))
        val hidden = Note(title = "Hidden needle")
        runBlocking { app.repository.save(tagged); app.repository.save(hidden) }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("Filter notes").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText(hidden.title).assertDoesNotExist()
        capture("filtered-list")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search notes and attachments").performTextInput("needle")
        compose.waitUntil(10_000) { vm.results.size == 1 }
        assertEquals(tagged.id, vm.results.single().noteId)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("New note").performClick()
        assertEquals(listOf(tag.id), vm.note!!.tagIds)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { vm.screen == Screen.LIST }
        assertEquals(2, app.repository.notes.value.size)
        compose.onNodeWithText(tagged.title).performClick()
        compose.onNodeWithContentDescription("Note menu").performClick()
        compose.onNodeWithText("Tags").performClick()
        compose.onNode(hasText("Work") and isToggleable()).performClick()
        compose.waitUntil(10_000) { vm.note!!.tagIds.isEmpty() }
        compose.onNodeWithText("Create tag").performClick()
        compose.onNodeWithText("Tag name").performTextInput("Personal")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.tags.any { it.name == "Personal" } && vm.note!!.tagIds.isNotEmpty() }
        runBlocking { vm.flush() }
        assertEquals(1, app.repository.notes.value.first { it.id == tagged.id }.tagIds.size)
        compose.runOnUiThread { app.lock() }
        compose.waitUntil { vm.tags.isEmpty() }
        assertEquals(NoteFilter(), vm.filter); assertFalse(vm.findOpen)
    }
    @Test fun filteredAccessibilityMoveAndTagManagementKeepNotes() {
        unlock()
        val tag = runBlocking { app.repository.createTag("Work") }
        runBlocking { app.repository.createTag("Other") }
        val notes = listOf(Note(title = "First", tagIds = listOf(tag.id)), Note(title = "Hidden"), Note(title = "Last", tagIds = listOf(tag.id)))
        runBlocking { notes.reversed().forEach { app.repository.save(it) } }
        compose.setContent { SecureNotesApp(vm, {}, {}, {}) }
        compose.onNodeWithContentDescription("Filter notes").performClick()
        compose.onNodeWithText("Work").performClick()
        val actions = compose.onNodeWithTag("note-row-${notes[2].id}").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Move up" }.action() }
        compose.waitUntil(10_000) { app.repository.notes.value.first().id == notes[2].id }
        assertEquals(notes[1], app.repository.notes.value[1])
        val start = compose.onNodeWithText("Last").fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithText("First").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start); advanceEventTime(700); moveTo(end, delayMillis = 400); up() }
        compose.waitUntil(10_000) { app.repository.notes.value == notes }
        compose.onNodeWithContentDescription("Filter notes").performClick()
        compose.onNodeWithText("Manage tags").performClick()
        compose.onNodeWithContentDescription("Rename Work").performClick()
        compose.onNodeWithText("Tag name").performTextReplacement("other")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("A tag with that name already exists.").assertIsDisplayed()
        compose.onNodeWithText("Tag name").performTextReplacement("Projects")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { vm.filterLabel == "Projects" }
        compose.waitUntil { compose.onAllNodesWithText("Tag name").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Delete Projects").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(10_000) { vm.filter == NoteFilter() }
        assertEquals(3, app.repository.notes.value.size)
        assertTrue(app.repository.notes.value.all { it.tagIds.isEmpty() })
    }
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
