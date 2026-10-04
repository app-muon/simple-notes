package dev.securenotes.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun validId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
val documentJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable enum class LineType {
    PARAGRAPH, HEADING1, HEADING2, BULLET, NUMBERED, CHECKLIST;
    val isList: Boolean get() = this == BULLET || this == NUMBERED || this == CHECKLIST
}
/** Formatting for one line of the body (text between newlines). */
@Serializable data class Line(val type: LineType = LineType.PARAGRAPH, val checked: Boolean = false)
/** Bold range in body character offsets, end exclusive. */
@Serializable data class BoldSpan(val start: Int, val end: Int)
/**
 * A note body: one plain string, one [Line] per line of it, bold ranges, and the note's attachments in display order.
 * Attachments live in their own section, never inside the text.
 */
@Serializable data class Document(
    val version: Int = 2,
    val text: String = "",
    val lines: List<Line> = listOf(Line()),
    val bold: List<BoldSpan> = emptyList(),
    val attachments: List<String> = emptyList(),
) {
    fun validate() {
        require(version == 2) { "Unsupported document version" }
        require(lines.size == text.count { it == '\n' } + 1) { "Line formats do not match the text" }
        var previousEnd = 0
        bold.forEach { require(it.start >= previousEnd && it.end > it.start && it.end <= text.length) { "Invalid bold range" }; previousEnd = it.end }
        require(attachments.distinct().size == attachments.size && attachments.all(::validId))
    }
}
@Serializable data class Note(
    val id: String = newId(), val title: String = "", val document: Document = Document(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val tagIds: List<String> = emptyList(),
) {
    val displayTitle: String get() = title.trim().ifEmpty { "Untitled" }
    val isEmpty: Boolean get() = title.isBlank() && document.text.isBlank() && document.attachments.isEmpty()
}
@Serializable enum class SortOrder(val label: String) {
    EDITED("Recently edited"), CREATED("Recently created"), ALPHABETICAL("Alphabetical")
}
fun sortedNotes(notes: List<Note>, order: SortOrder): List<Note> = when (order) {
    SortOrder.EDITED -> notes.sortedWith(compareByDescending<Note> { it.updatedAt }.thenBy { it.id })
    SortOrder.CREATED -> notes.sortedWith(compareByDescending<Note> { it.createdAt }.thenBy { it.id })
    SortOrder.ALPHABETICAL -> {
        val collator = java.text.Collator.getInstance(java.util.Locale.ENGLISH).apply { strength = java.text.Collator.PRIMARY }
        notes.sortedWith(Comparator<Note> { a, b -> collator.compare(a.displayTitle, b.displayTitle) }.thenBy { it.id })
    }
}

/** Immutable snapshots deliberately live only in the active editing session. */
class EditHistory(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val undo = ArrayDeque<Note>()
    private val redo = ArrayDeque<Note>()
    private var lastTypingAt: Long? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun hasTagsOutside(ids: Set<String>) = (undo.asSequence() + redo.asSequence()).any { note -> note.tagIds.any { it !in ids } }
    /** Consecutive typing shares one undo step until a pause or a history/session boundary. */
    fun record(note: Note, typing: Boolean = false) {
        val time = now()
        val previous = lastTypingAt
        if (!typing || previous == null || time - previous > 1_000 || redo.isNotEmpty()) {
            undo.addLast(note)
            if (undo.size > 100) undo.removeFirst()
        }
        redo.clear()
        lastTypingAt = if (typing) time else null
    }
    fun undo(current: Note): Note? {
        lastTypingAt = null
        return if (undo.isEmpty()) null else undo.removeLast().also { redo.addLast(current) }
    }
    fun redo(current: Note): Note? {
        lastTypingAt = null
        return if (redo.isEmpty()) null else redo.removeLast().also { undo.addLast(current) }
    }
    fun clear() { undo.clear(); redo.clear(); lastTypingAt = null }
}
