package dev.securenotes.document

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun validId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
val documentJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable enum class BlockType { PARAGRAPH, HEADING1, HEADING2, BULLET, NUMBERED, CHECKLIST, IMAGE, FILE }
@Serializable data class BoldSpan(val start: Int, val end: Int)
@Serializable data class Block(
    val id: String = newId(),
    val type: BlockType = BlockType.PARAGRAPH,
    val text: String = "",
    val bold: List<BoldSpan> = emptyList(),
    val checked: Boolean = false,
    val attachmentId: String? = null,
) {
    val isAttachment: Boolean get() = type == BlockType.IMAGE || type == BlockType.FILE
}
@Serializable data class Document(val version: Int = 1, val blocks: List<Block> = listOf(Block())) {
    fun validate() {
        require(version == 1) { "Unsupported document version" }
        require(blocks.map { it.id }.distinct().size == blocks.size)
        blocks.forEach { b ->
            require(validId(b.id))
            require(b.bold.all { it.start >= 0 && it.end > it.start && it.end <= b.text.length })
            require(if (b.isAttachment) b.attachmentId?.let(::validId) == true else b.attachmentId == null)
        }
    }
}
@Serializable data class Note(
    val id: String = newId(), val title: String = "", val document: Document = Document(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
) {
    val displayTitle: String get() = title.trim().ifEmpty { "Untitled" }
    val isEmpty: Boolean get() = title.isBlank() && document.blocks.none { it.text.isNotBlank() || it.isAttachment }
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
class EditHistory {
    private val undo = ArrayDeque<Note>()
    private val redo = ArrayDeque<Note>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun record(note: Note) { undo.addLast(note); redo.clear(); if (undo.size > 100) undo.removeFirst() }
    fun undo(current: Note): Note? = if (undo.isEmpty()) null else undo.removeLast().also { redo.addLast(current) }
    fun redo(current: Note): Note? = if (redo.isEmpty()) null else redo.removeLast().also { undo.addLast(current) }
    fun clear() { undo.clear(); redo.clear() }
}
