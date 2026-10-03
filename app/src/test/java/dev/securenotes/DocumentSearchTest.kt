package dev.securenotes

import dev.securenotes.document.*
import dev.securenotes.search.SearchText
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DocumentSearchTest {
    @Test fun `alphabetical sorting handles English accents and case`() {
        val notes = listOf(Note(title = "Zebra"), Note(title = "Élan"), Note(title = "apple"))
        assertEquals(listOf("apple", "Élan", "Zebra"), sortedNotes(notes, SortOrder.ALPHABETICAL).map { it.title })
    }
    @Test fun `extraction chunks keep complete words and combining marks`() {
        val text = "x ".repeat(4094) + "re\u0301ceipt boundary " + "y ".repeat(4200)
        val chunks = SearchText.chunks(text).toList()
        assertEquals(text, chunks.joinToString(""))
        assertEquals(SearchText.tokens(text).map { it.term }, chunks.flatMap { SearchText.tokens(it) }.map { it.term })
        assertTrue(chunks.any { it.contains("re\u0301ceipt") })
    }
    @Test fun `normalization preserves original offsets for accented text`() {
        val tokens = SearchText.tokens("A café and Cafe\u0301, RECEIPT 123")
        assertEquals(listOf("a", "cafe", "and", "cafe", "receipt", "123"), tokens.map { it.term })
        assertEquals(2, tokens[1].start); assertEquals(6, tokens[1].end)
        assertEquals("Cafe\u0301", "A café and Cafe\u0301, RECEIPT 123".substring(tokens[3].start, tokens[3].end))
    }
    @Test fun `fuzzy matching accepts one typo but rejects noise and short fuzzy terms`() {
        for (word in listOf("reciept", "receit", "reeeipt", "receipts")) assertTrue(word, SearchText.score(word, "receipt") > 0)
        assertEquals(0, SearchText.score("recipe", "receipt"))
        assertEquals(0, SearchText.score("cat", "car"))
        assertTrue(SearchText.score("receipt", "receipt") > SearchText.score("reciept", "receipt"))
        assertTrue(SearchText.score("rece", "receipt") > 0)
    }
    @Test fun `structured notes roundtrip without losing formatting or attachment order`() {
        val doc = Document(text = "Title\nDone", lines = listOf(Line(LineType.HEADING1), Line(LineType.CHECKLIST, checked = true)),
            bold = listOf(BoldSpan(0, 5)), attachments = listOf(newId(), newId()))
        doc.validate()
        assertEquals(doc, documentJson.decodeFromString<Document>(documentJson.encodeToString(doc)))
    }
    @Test fun `empty draft ignores whitespace but retains attachments and untitled text`() {
        assertTrue(Note(title = " \n", document = Document(text = "  ")).isEmpty)
        val note = Note(document = Document(text = "Keep me"))
        assertFalse(note.isEmpty); assertEquals("Untitled", note.displayTitle)
        assertFalse(Note(document = Document(attachments = listOf(newId()))).isEmpty)
    }
    @Test fun `sort modes and edit history maintain expected order`() {
        val a = Note(title = "Zulu", createdAt = 1, updatedAt = 9)
        val b = Note(title = "Apple", createdAt = 2, updatedAt = 3)
        assertEquals(a, sortedNotes(listOf(a, b), SortOrder.EDITED).first())
        assertEquals(b, sortedNotes(listOf(a, b), SortOrder.CREATED).first())
        assertEquals(b, sortedNotes(listOf(a, b), SortOrder.ALPHABETICAL).first())
        val history = EditHistory(); history.record(a)
        assertEquals(a, history.undo(b)); assertEquals(b, history.redo(a)); history.clear(); assertNull(history.undo(b))
    }
    @Test fun `invalid document versions and spans are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Document(version = 1).validate() }
        assertThrows(IllegalArgumentException::class.java) { Document(text = "x", bold = listOf(BoldSpan(0, 2))).validate() }
        assertThrows(IllegalArgumentException::class.java) { Document(text = "a\nb").validate() }
        assertThrows(IllegalArgumentException::class.java) { Document(text = "abcd", bold = listOf(BoldSpan(0, 3), BoldSpan(2, 4))).validate() }
        assertThrows(IllegalArgumentException::class.java) { Document(attachments = listOf("not-a-uuid")).validate() }
    }
}
