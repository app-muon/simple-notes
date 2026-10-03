package dev.securenotes.document

/** A document after an edit, and where the cursor belongs. */
data class Edited(val document: Document, val selection: Int)

/** Editing rules for the single-text note body. Pure Kotlin, so the editor's behaviour is unit-tested here. */
object DocumentEdits {
    fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>().apply { add(0) }
        text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
        return starts.toIntArray()
    }
    fun lineAt(text: String, offset: Int): Int {
        val end = offset.coerceIn(0, text.length)
        var line = 0
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
    fun lineStart(text: String, line: Int): Int = lineStarts(text)[line]
    fun lineEnd(text: String, line: Int): Int = text.indexOf('\n', lineStart(text, line)).let { if (it < 0) text.length else it }
    fun lineText(document: Document, line: Int): String = document.text.substring(lineStart(document.text, line), lineEnd(document.text, line))
    /** Position within a run of consecutive numbered lines, starting at 1. */
    fun numberFor(document: Document, line: Int): Int {
        var number = 0; var i = line
        while (i >= 0 && document.lines[i].type == LineType.NUMBERED) { number++; i-- }
        return number
    }
    /** Lines touched by a selection; a selection ending just after a newline does not include the next line. */
    fun selectedLines(document: Document, selectionStart: Int, selectionEnd: Int): IntRange {
        val a = minOf(selectionStart, selectionEnd); val b = maxOf(selectionStart, selectionEnd)
        return lineAt(document.text, a)..lineAt(document.text, if (b > a) b - 1 else b)
    }

    /**
     * Replaces `text[start, end)` with [inserted] and decides line formats:
     * - Enter on an empty list item ends the list instead of adding a line.
     * - Backspace over the newline before a list item first clears that item's list format.
     * - Enter continues a list (a new checklist item starts unchecked); after a heading or paragraph the new line is a paragraph.
     *   Enter at the start of a non-empty line pushes the line down unchanged.
     * - Other inserted lines (paste, shared text) are plain paragraphs.
     * - Merged lines keep the format of the first line.
     */
    fun replace(document: Document, start: Int, end: Int, inserted: String): Edited {
        val text = document.text
        require(start in 0..end && end <= text.length) { "Invalid range" }
        val startLine = lineAt(text, start)
        val endLine = lineAt(text, end)
        val current = document.lines[startLine]
        val currentStart = lineStart(text, startLine)
        val currentEnd = lineEnd(text, startLine)
        if (inserted == "\n" && start == end && current.type.isList && currentStart == currentEnd)
            return Edited(document.copy(lines = document.lines.updated(startLine, Line())), start)
        if (inserted.isEmpty() && end == start + 1 && text[start] == '\n' && document.lines[startLine + 1].type.isList)
            return Edited(document.copy(lines = document.lines.updated(startLine + 1, Line())), end)
        val (first, added) = when {
            inserted == "\n" && start == currentStart && currentStart != currentEnd && start == end ->
                (if (current.type.isList) Line(current.type) else Line()) to listOf(current)
            inserted == "\n" -> current to listOf(if (current.type.isList) Line(current.type) else Line())
            else -> current to List(inserted.count { it == '\n' }) { Line() }
        }
        val lines = document.lines.subList(0, startLine) + first + added + document.lines.subList(endLine + 1, document.lines.size)
        val bold = normalize(document.bold.mapNotNull { span -> shift(span, start, end, inserted.length) })
        return Edited(document.copy(text = text.substring(0, start) + inserted + text.substring(end), lines = lines, bold = bold), start + inserted.length)
    }
    /** Bold follows text; typing at a bold range's edge stays unbold, typing inside it stays bold. */
    private fun shift(span: BoldSpan, start: Int, end: Int, length: Int): BoldSpan? {
        val delta = length - (end - start)
        val s = when { span.start < start -> span.start; span.start >= end -> span.start + delta; else -> start + length }
        val e = when { span.end <= start -> span.end; span.end >= end -> span.end + delta; else -> start }
        return if (e > s) BoldSpan(s, e) else null
    }
    fun normalize(spans: List<BoldSpan>): List<BoldSpan> {
        val result = mutableListOf<BoldSpan>()
        spans.filter { it.end > it.start }.sortedBy { it.start }.forEach { span ->
            val last = result.lastOrNull()
            if (last != null && span.start <= last.end) result[result.lastIndex] = BoldSpan(last.start, maxOf(last.end, span.end)) else result += span
        }
        return result
    }
    fun isBold(document: Document, start: Int, end: Int): Boolean = end > start && document.bold.any { it.start <= start && it.end >= end }
    fun toggleBold(document: Document, selectionStart: Int, selectionEnd: Int): Document {
        val start = minOf(selectionStart, selectionEnd); val end = maxOf(selectionStart, selectionEnd)
        if (end <= start) return document
        val bold = if (isBold(document, start, end)) document.bold.flatMap { span ->
            if (span.end <= start || span.start >= end) listOf(span)
            else listOfNotNull(BoldSpan(span.start, start).takeIf { span.start < start }, BoldSpan(end, span.end).takeIf { span.end > end })
        } else document.bold + BoldSpan(start, end)
        return document.copy(bold = normalize(bold))
    }
    /** The format shared by every selected line, or null when they differ. */
    fun activeType(document: Document, selectionStart: Int, selectionEnd: Int): LineType? =
        selectedLines(document, selectionStart, selectionEnd).map { document.lines[it].type }.distinct().singleOrNull()
    /** Toolbar toggle: applies [type] to every selected line, or returns them to paragraphs when all already have it. */
    fun setLineType(document: Document, selectionStart: Int, selectionEnd: Int, type: LineType): Document {
        val selected = selectedLines(document, selectionStart, selectionEnd)
        val target = if (selected.all { document.lines[it].type == type }) LineType.PARAGRAPH else type
        return document.copy(lines = document.lines.mapIndexed { i, line ->
            if (i in selected) Line(target, target == LineType.CHECKLIST && line.type == LineType.CHECKLIST && line.checked) else line
        })
    }
    fun setChecked(document: Document, line: Int, checked: Boolean): Document =
        if (document.lines[line].type != LineType.CHECKLIST) document else document.copy(lines = document.lines.updated(line, document.lines[line].copy(checked = checked)))
    /** Adds shared or pasted text as plain paragraphs; an empty body is replaced. */
    fun appendText(document: Document, text: String): Document {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (document.text.isEmpty()) replace(document.copy(lines = listOf(Line())), 0, 0, clean).document
        else replace(document, document.text.length, document.text.length, "\n$clean").document
    }
    private fun <T> List<T>.updated(index: Int, value: T): List<T> = toMutableList().apply { this[index] = value }
}
