package dev.securenotes

import dev.securenotes.document.Note
import dev.securenotes.storage.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingEditsTest {
    @Test fun `debounce saves only newest snapshot and respects one second typing bound`() = runTest {
        val saved = mutableListOf<PendingEdit>()
        lateinit var pending: PendingEdits
        pending = PendingEdits(backgroundScope, { testScheduler.currentTime }) {
            pending.snapshot().forEach { saved += it; pending.committed(it) }
        }
        pending.start()
        val note = Note(title = "first")
        pending.put(PendingEdit(note, false)); runCurrent()
        repeat(9) { n ->
            advanceTimeBy(100); pending.put(PendingEdit(note.copy(title = "edit $n"), false)); runCurrent()
        }
        assertTrue(saved.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertEquals("edit 8", saved.single().note.title)
        pending.put(PendingEdit(note.copy(title = "last"), false)); runCurrent()
        advanceTimeBy(299); runCurrent(); assertEquals(1, saved.size)
        advanceTimeBy(1); runCurrent(); assertEquals("last", saved.last().note.title)
        pending.stop()
    }
    @Test fun `committing older snapshot cannot remove edits received during save`() = runTest {
        val pending = PendingEdits(backgroundScope) { }
        val old = PendingEdit(Note(title = "old"), false)
        val latest = old.copy(note = old.note.copy(title = "new"))
        pending.put(old); pending.put(latest); pending.committed(old)
        assertEquals(listOf(latest), pending.snapshot())
        pending.stop(); assertEquals(listOf(latest), pending.snapshot())
    }
}
