package dev.securenotes

import dev.securenotes.document.*
import dev.securenotes.document.LineType.*
import org.junit.Assert.*
import org.junit.Test

class DocumentEditsTest {
    private fun doc(vararg lines: Pair<String, LineType>, bold: List<BoldSpan> = emptyList()) =
        Document(text = lines.joinToString("\n") { it.first }, lines = lines.map { Line(it.second) }, bold = bold).also { it.validate() }
    private fun Document.types() = lines.map { it.type }
    private fun enter(d: Document, at: Int) = DocumentEdits.replace(d, at, at, "\n")

    @Test fun `enter continues lists and ends them on an empty item`() {
        val bullets = doc("Milk" to BULLET)
        val continued = enter(bullets, 4)
        assertEquals("Milk\n", continued.document.text); assertEquals(listOf(BULLET, BULLET), continued.document.types()); assertEquals(5, continued.selection)
        val ended = enter(continued.document, 5)
        assertEquals("Milk\n", ended.document.text); assertEquals(listOf(BULLET, PARAGRAPH), ended.document.types())
        val checked = Document(text = "Done", lines = listOf(Line(CHECKLIST, checked = true)))
        assertEquals(listOf(Line(CHECKLIST, true), Line(CHECKLIST, false)), enter(checked, 4).document.lines)
        assertEquals(listOf(NUMBERED, NUMBERED), enter(doc("One" to NUMBERED), 3).document.types())
    }
    @Test fun `enter after a heading starts a paragraph but enter at its start pushes it down`() {
        val heading = doc("Title" to HEADING1)
        assertEquals(listOf(HEADING1, PARAGRAPH), enter(heading, 5).document.types())
        assertEquals(listOf(HEADING1, PARAGRAPH), enter(heading, 2).document.types())
        val pushed = enter(heading, 0)
        assertEquals("\nTitle", pushed.document.text); assertEquals(listOf(PARAGRAPH, HEADING1), pushed.document.types())
        val task = Document(text = "Task", lines = listOf(Line(CHECKLIST, checked = true)))
        assertEquals(listOf(Line(CHECKLIST), Line(CHECKLIST, true)), enter(task, 0).document.lines)
    }
    @Test fun `backspace at a list item start clears the format then joins`() {
        val d = doc("Intro" to PARAGRAPH, "item" to BULLET)
        val cleared = DocumentEdits.replace(d, 5, 6, "")
        assertEquals(d.text, cleared.document.text); assertEquals(listOf(PARAGRAPH, PARAGRAPH), cleared.document.types()); assertEquals(6, cleared.selection)
        val joined = DocumentEdits.replace(cleared.document, 5, 6, "")
        assertEquals("Introitem", joined.document.text); assertEquals(listOf(PARAGRAPH), joined.document.types())
        val heading = DocumentEdits.replace(doc("A" to HEADING2, "b" to PARAGRAPH), 1, 2, "")
        assertEquals("Ab", heading.document.text); assertEquals(listOf(HEADING2), heading.document.types())
    }
    @Test fun `deleting across lines keeps the first line format and pasted lines are paragraphs`() {
        val d = doc("one" to BULLET, "two" to HEADING1, "three" to CHECKLIST)
        val deleted = DocumentEdits.replace(d, 1, 9, "")
        assertEquals("ohree", deleted.document.text); assertEquals(listOf(BULLET), deleted.document.types())
        val pasted = DocumentEdits.replace(doc("ab" to BULLET), 1, 1, "x\ny\nz")
        assertEquals("ax\ny\nzb", pasted.document.text); assertEquals(listOf(BULLET, PARAGRAPH, PARAGRAPH), pasted.document.types())
        pasted.document.validate()
    }
    @Test fun `bold follows edits and stays out of text typed at its edges`() {
        val d = doc("hello world" to PARAGRAPH, bold = listOf(BoldSpan(6, 11)))
        assertEquals(listOf(BoldSpan(7, 12)), DocumentEdits.replace(d, 0, 0, "X").document.bold)
        assertEquals(listOf(BoldSpan(6, 11)), DocumentEdits.replace(d, 6, 6, "Y").document.bold.map { BoldSpan(it.start - 1, it.end - 1) })
        assertEquals(listOf(BoldSpan(6, 11)), DocumentEdits.replace(d, 11, 11, "!").document.bold)
        assertEquals(listOf(BoldSpan(6, 12)), DocumentEdits.replace(d, 8, 8, "r").document.bold)
        assertEquals(listOf(BoldSpan(6, 8)), DocumentEdits.replace(d, 8, 11, "").document.bold)
        assertTrue(DocumentEdits.replace(d, 5, 11, "").document.bold.isEmpty())
    }
    @Test fun `bold toggles across lines and splits when removed`() {
        val d = doc("first" to PARAGRAPH, "second" to BULLET)
        val bolded = DocumentEdits.toggleBold(d, 2, 9)
        assertEquals(listOf(BoldSpan(2, 9)), bolded.bold); assertTrue(DocumentEdits.isBold(bolded, 3, 8))
        assertEquals(listOf(BoldSpan(2, 4), BoldSpan(6, 9)), DocumentEdits.toggleBold(bolded, 4, 6).bold)
        assertEquals(listOf(BoldSpan(0, 9)), DocumentEdits.toggleBold(DocumentEdits.toggleBold(bolded, 4, 6), 0, 9).bold)
        assertSame(d, DocumentEdits.toggleBold(d, 3, 3))
    }
    @Test fun `line format toggles apply to every selected line`() {
        val d = doc("a" to PARAGRAPH, "b" to BULLET, "c" to PARAGRAPH)
        val bullets = DocumentEdits.setLineType(d, 0, 5, BULLET)
        assertEquals(listOf(BULLET, BULLET, BULLET), bullets.types())
        assertEquals(listOf(PARAGRAPH, PARAGRAPH, PARAGRAPH), DocumentEdits.setLineType(bullets, 0, 5, BULLET).types())
        assertEquals(listOf(HEADING1, HEADING1, PARAGRAPH), DocumentEdits.setLineType(d, 0, 4, HEADING1).types())
        assertEquals(BULLET, DocumentEdits.activeType(d, 2, 3)); assertNull(DocumentEdits.activeType(d, 0, 3))
        assertEquals(listOf(Line(CHECKLIST, true), Line(BULLET), Line()), DocumentEdits.setChecked(DocumentEdits.setLineType(d, 0, 0, CHECKLIST), 0, true).lines)
    }
    @Test fun `numbers restart after a non-numbered line and shared text becomes paragraphs`() {
        val d = doc("a" to NUMBERED, "b" to NUMBERED, "x" to PARAGRAPH, "c" to NUMBERED)
        assertEquals(listOf(1, 2, 0, 1), d.lines.indices.map { DocumentEdits.numberFor(d, it) })
        val shared = DocumentEdits.appendText(Document(), "one\r\ntwo")
        assertEquals("one\ntwo", shared.text); shared.validate()
        val appended = DocumentEdits.appendText(doc("x" to BULLET), "y")
        assertEquals("x\ny", appended.text); assertEquals(listOf(BULLET, PARAGRAPH), appended.types())
    }
}
