package dev.securenotes

import dev.securenotes.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindSessionTest {
    @Test fun `query changes retain highlights but pending results cannot navigate`() = runTest {
        var jumps = 0
        val session = FindSession(this, StandardTestDispatcher(testScheduler), publish = {}, scroll = { jumps++ })
        session.search("banana", "banana", "ana", true)
        assertTrue(session.state.pending); assertTrue(session.state.matches.isEmpty())
        advanceUntilIdle()
        val old = session.state
        session.move(2)
        session.search("banana", "banana", "ban", true)
        assertTrue(session.state.pending)
        assertEquals(old.matches, session.state.matches)
        assertEquals(old.queryGeneration + 1, session.state.queryGeneration)
        assertEquals(old.documentGeneration, session.state.documentGeneration)
        val pending = session.state
        session.move(1); assertEquals(pending, session.state)
        advanceUntilIdle()
        assertFalse(session.state.pending); assertEquals(2, session.state.matches.size)
        assertEquals(1, session.state.activeIndex) // Old index clamps to the last remaining match.
        assertEquals(3, jumps)
    }

    @Test fun `document changes invalidate highlights and reuse independent preparations`() = runTest {
        val prepared = mutableListOf<String>()
        val session = FindSession(this, StandardTestDispatcher(testScheduler), prepare = { text, check ->
            prepared += text; FindText.prepare(text, check)
        }, publish = {}, scroll = {})
        session.search("Title", "cafe", "a", true); advanceUntilIdle()
        session.search("Title", "cafe", "cafe", true); advanceUntilIdle()
        assertEquals(listOf("Title", "cafe"), prepared)
        val revision = session.state.documentGeneration
        session.search("Title", "café café", "cafe", false)
        assertTrue(session.state.pending); assertTrue(session.state.matches.isEmpty())
        assertTrue(session.state.bodyMatchesByLine.isEmpty())
        advanceUntilIdle()
        assertEquals(revision + 1, session.state.documentGeneration)
        assertEquals(listOf("Title", "cafe", "café café"), prepared)
        session.search("Other", "café café", "cafe", false); advanceUntilIdle()
        assertEquals("Other", prepared.last()); assertEquals(4, prepared.size)
        session.clear(); session.search("Other", "café café", "cafe", true); advanceUntilIdle()
        assertEquals(6, prepared.size)
    }

    @Test fun `superseded work and cleared queries never publish late results`() = runTest {
        var jumps = 0
        val published = mutableListOf<FindState>()
        val worker = StandardTestDispatcher(testScheduler)
        val session = FindSession(this, worker, publish = { published += it }, scroll = { jumps++ })
        session.search("alpha", "beta", "alpha", true)
        advanceTimeBy(149)
        session.search("alpha", "beta", "beta", true)
        advanceUntilIdle()
        assertEquals(HitKind.BODY, session.state.active!!.kind); assertEquals(1, jumps)
        assertFalse(published.any { it.query == "alpha" && it.matches.isNotEmpty() })
        session.search("alpha", "beta", "alpha", true)
        session.search("alpha", "beta", "", true)
        assertFalse(session.state.pending); assertTrue(session.state.matches.isEmpty())
        advanceUntilIdle(); assertEquals("", session.state.query); assertEquals(1, jumps)
        session.search("alpha", "beta", "beta", true); session.clear()
        advanceUntilIdle(); assertEquals(FindState(), session.state)
    }

    @Test fun `cancelled computations cannot publish even if preparation finishes`() = runTest {
        lateinit var session: FindSession
        var cancelled = false
        session = FindSession(this, StandardTestDispatcher(testScheduler), prepare = { text, _ ->
            if (!cancelled) { cancelled = true; session.clear() }
            FindText.prepare(text) // Deliberately uncooperative preparation.
        }, publish = {}, scroll = { fail("Stale scroll") })
        session.search("title", "body", "title", true)
        advanceUntilIdle(); assertEquals(FindState(), session.state)
    }

    @Test fun `close bypasses debounce while clearing public state and supports cancellation`() = runTest {
        var restored: FindState? = null
        val session = FindSession(this, StandardTestDispatcher(testScheduler), publish = {}, scroll = { fail("Close must not scroll") })
        session.search("title", "match", "match", true)
        session.close(true) { restored = it }
        assertEquals(FindState(), session.state)
        runCurrent() // No virtual time advance: close does not wait 150 ms.
        assertEquals(0L, testScheduler.currentTime); assertEquals(FindMatch(HitKind.BODY, 0, 5), restored!!.active)
        restored = null
        session.search("title", "match", "match", true)
        session.close(true) { restored = it }; session.clear()
        advanceUntilIdle(); assertNull(restored)
    }

    @Test fun `editor interaction cancels scrolling while results still complete`() = runTest {
        var jumps = 0
        val session = FindSession(this, StandardTestDispatcher(testScheduler), publish = {}, scroll = { jumps++ })
        session.search("title", "match", "match", true); session.cancelScroll()
        advanceUntilIdle(); assertEquals(1, session.state.matches.size); assertEquals(0, jumps)
        session.move(1); assertEquals(1, jumps)
    }

    @Test fun `multiline grouping includes every intersected line and global result index`() {
        val body = "alpha\nbeta\ngamma"
        val matches = FindText.matches("alpha", body, "alpha") + FindMatch(HitKind.BODY, 3, 13)
        val grouped = groupBodyMatches(body, matches)
        assertEquals(listOf(1, 2), grouped.getValue(0).map { it.index })
        assertEquals(listOf(2), grouped.getValue(1).map { it.index })
        assertEquals(listOf(2), grouped.getValue(2).map { it.index })
        assertEquals(listOf(0), groupBodyMatches("a\nb", listOf(FindMatch(HitKind.BODY, 0, 2))).keys.toList())
    }

    @Test fun `title whitespace maps clips and excludes placeholder highlights`() {
        val title = "  café 😀  "
        val window = titleWindow(title)
        assertEquals("café 😀", window.text); assertEquals(2, window.offset)
        assertEquals(FindMatch(HitKind.TITLE, 0, 4), window.clip(FindText.matches(title, "", "cafe").single()))
        assertEquals(5, window.visibleOffset(7))
        assertEquals(FindMatch(HitKind.TITLE, 0, 7), window.clip(FindMatch(HitKind.TITLE, 0, title.length)))
        assertNull(window.clip(FindMatch(HitKind.TITLE, 0, 2)))
        assertNull(titleWindow("  ").clip(FindMatch(HitKind.TITLE, 0, 2)))
        assertTrue(titleWindow("").placeholder)
    }
}
