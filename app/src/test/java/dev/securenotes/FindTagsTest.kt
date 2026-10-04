package dev.securenotes

import dev.securenotes.document.*
import dev.securenotes.search.*
import dev.securenotes.backup.*
import org.junit.Assert.*
import org.junit.Test

class FindTagsTest {
    @Test fun `find is literal partial accent insensitive and ordered title before body`() {
        val matches = FindText.matches("CAFÉ café!", "decafe\u0301, café!", "CaFe")
        assertEquals(listOf(FindMatch(HitKind.TITLE, 0, 4), FindMatch(HitKind.TITLE, 5, 9), FindMatch(HitKind.BODY, 2, 7), FindMatch(HitKind.BODY, 9, 13)), matches)
        assertEquals(listOf(FindMatch(HitKind.BODY, 9, 14)), FindText.matches("", "decafe\u0301, café!", "café!"))
        assertTrue(FindText.matches("cat", "cot", "c.t").isEmpty())
    }
    @Test fun `offsets cover original combining sequences and complete emoji`() {
        val body = "😀 e\u0301 😀 café"
        assertEquals(listOf(FindMatch(HitKind.BODY, 3, 5), FindMatch(HitKind.BODY, 12, 13)), FindText.matches("", body, "é"))
        assertEquals(listOf(FindMatch(HitKind.BODY, 0, 2), FindMatch(HitKind.BODY, 6, 8)), FindText.matches("", body, "😀"))
        assertEquals(FindMatch(HitKind.BODY, 9, 13), FindText.matches("", body, "CAFE").single())
    }
    @Test fun `phrases punctuation and newline spanning matches are literal`() {
        assertEquals(listOf(FindMatch(HitKind.BODY, 2, 14)), FindText.matches("", "a first\nsecond z", "first\nsecond"))
        assertEquals(2, FindText.matches("hello, world", "Hello, World", "hello, world").size)
        assertTrue(FindText.matches("hello", "world", "hello world").isEmpty())
        assertEquals(2, FindText.matches("banana", "", "ana").size)
    }
    @Test fun `empty no matches and navigation wrap safely`() {
        assertTrue(FindText.matches("café", "body", "").isEmpty())
        assertTrue(FindText.matches("café", "body", "\u0301").isEmpty())
        val state = FindState("a", FindText.matches("a", "a", "a"), 0)
        assertEquals(1, state.move(-1).activeIndex)
        assertEquals(0, state.move(1).move(1).activeIndex)
        assertEquals(FindState(), FindState().move(1))
    }
    @Test fun `matcher supports cooperative cancellation`() {
        var calls = 0
        assertThrows(InterruptedException::class.java) { FindText.matches("", "a".repeat(10_000), "a") { if (++calls == 20) throw InterruptedException() } }
    }
    @Test fun `tags normalize whitespace preserve capitals and reject duplicates`() {
        val tag = Tag(name = "My Work")
        assertEquals("My Work", validateTagName("  My\t Work \n", emptyList()))
        assertThrows(IllegalArgumentException::class.java) { validateTagName("my  WORK", listOf(tag)) }
        assertThrows(IllegalArgumentException::class.java) { validateTagName(" \t\u00a0", emptyList()) }
        assertEquals("MY work", validateTagName("MY work", listOf(tag), tag.id))
    }
    @Test fun `subset ordering preserves hidden slots including accessibility swaps`() {
        val original = listOf("A", "x", "B", "y", "C")
        assertEquals(listOf("C", "x", "A", "y", "B"), reorderSubset(original, listOf("C", "A", "B")))
        assertEquals(listOf("B", "x", "A", "y", "C"), reorderSubset(original, listOf("B", "A", "C")))
        assertEquals(original, reorderSubset(original, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { reorderSubset(original, listOf("A", "A")) }
        assertThrows(IllegalArgumentException::class.java) { reorderSubset(original, listOf("missing")) }
    }
    @Test fun `filters include multiple assignments and tags do not make a draft nonempty`() {
        val note = Note(tagIds = listOf("a", "b"))
        assertTrue(note.isEmpty)
        assertTrue(NoteFilter().includes(note)); assertTrue(NoteFilter(tagId = "b").includes(note))
        assertFalse(NoteFilter(untagged = true).includes(note)); assertTrue(NoteFilter(untagged = true).includes(Note()))
    }
    @Test fun `tag manifest roundtrip and invalid references`() {
        val tag = Tag(name = "Work"); val unused = Tag(name = "Unused")
        val note = Note(title = "Tagged", tagIds = listOf(tag.id))
        val manifest = BackupManifest(notes = listOf(note), attachments = emptyList(), order = listOf(note.id), tags = listOf(tag, unused))
        val encoded = documentJson.encodeToString(BackupManifest.serializer(), manifest)
        assertEquals(manifest, documentJson.decodeFromString<BackupManifest>(encoded))
        BackupService.validate(manifest)
        for (invalid in listOf(manifest.copy(tags = emptyList()), manifest.copy(tags = listOf(tag, tag)),
            manifest.copy(tags = listOf(tag, Tag(name = "WORK"))), manifest.copy(tags = listOf(tag.copy(name = " Work "))),
            manifest.copy(notes = listOf(note.copy(tagIds = listOf(tag.id, tag.id)))), manifest.copy(version = 2))) {
            assertThrows(IllegalArgumentException::class.java) { BackupService.validate(invalid) }
        }
        val legacy = """{"version":2,"notes":[{"id":"${note.id}","title":"Old"}],"attachments":[],"order":["${note.id}"]}"""
        val restored = documentJson.decodeFromString<BackupManifest>(legacy)
        BackupService.validate(restored); assertTrue(restored.tags.isEmpty()); assertTrue(restored.notes.single().tagIds.isEmpty())
    }
}
