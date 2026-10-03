package dev.securenotes

import dev.securenotes.document.EditHistory
import dev.securenotes.document.Note
import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    private var time = 0L
    private val history = EditHistory { time }
    private val empty = Note()
    private val first = empty.copy(title = "A")
    private val second = empty.copy(title = "AB")

    @Test fun `typing coalesces until a pause and formatting is a separate step`() {
        history.record(empty, typing = true)
        time += 100
        history.record(first, typing = true)
        time += 1_001
        history.record(second, typing = true)
        val third = second.copy(title = "ABC")
        history.record(third)
        assertEquals(third, history.undo(third.copy(title = "Formatted")))
        assertEquals(second, history.undo(third))
        assertEquals(empty, history.undo(second))
        assertFalse(history.canUndo)
    }

    @Test fun `typing immediately after undo clears redo and can itself be undone`() {
        history.record(empty, typing = true)
        assertEquals(empty, history.undo(first))
        assertTrue(history.canRedo)
        time += 1
        history.record(empty, typing = true)
        val replacement = empty.copy(title = "B")
        assertFalse(history.canRedo)
        assertNull(history.redo(replacement))
        assertEquals(empty, history.undo(replacement))
    }

    @Test fun `typing immediately after redo starts a new undo step`() {
        history.record(empty, typing = true)
        assertEquals(empty, history.undo(first))
        assertEquals(first, history.redo(empty))
        time += 1
        history.record(first, typing = true)
        assertEquals(first, history.undo(second))
        assertEquals(empty, history.undo(first))
    }

    @Test fun `clearing a note session resets its typing group`() {
        history.record(empty, typing = true)
        history.clear()
        time += 1
        val other = Note(title = "Another note")
        history.record(other, typing = true)
        assertEquals(other, history.undo(other.copy(title = "Changed")))
        assertFalse(history.canUndo)
        history.clear()
        assertFalse(history.canRedo)
    }
}
