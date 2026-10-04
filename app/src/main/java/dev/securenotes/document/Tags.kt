package dev.securenotes.document

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable data class Tag(val id: String = newId(), val name: String)

fun tagName(value: String): String = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
fun tagKey(value: String): String = tagName(value).lowercase(Locale.ROOT)
fun validateTagName(value: String, tags: List<Tag>, exceptId: String? = null): String {
    val name = tagName(value)
    require(name.isNotEmpty()) { "Enter a tag name." }
    require(tags.none { it.id != exceptId && tagKey(it.name) == tagKey(name) }) { "A tag with that name already exists." }
    return name
}

/** Session-only filter; never saved outside the encrypted vault or across locking. */
data class NoteFilter(val tagId: String? = null, val untagged: Boolean = false) {
    fun includes(note: Note): Boolean = when {
        tagId != null -> tagId in note.tagIds
        untagged -> note.tagIds.isEmpty()
        else -> true
    }
}

/** Replaces just the selected slots, retaining all hidden notes at their original positions. */
fun reorderSubset(order: List<String>, visible: List<String>): List<String> {
    require(visible.distinct().size == visible.size && order.containsAll(visible))
    val selected = visible.toSet()
    val replacements = visible.iterator()
    return order.map { if (it in selected) replacements.next() else it }
}
