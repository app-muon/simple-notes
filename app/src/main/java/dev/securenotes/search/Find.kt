package dev.securenotes.search

import java.text.Normalizer
import java.util.Locale

data class FindMatch(val kind: HitKind, val start: Int, val end: Int)
data class IndexedFindMatch(val index: Int, val match: FindMatch)

data class FindState(
    val query: String = "", val matches: List<FindMatch> = emptyList(), val activeIndex: Int = -1,
    val pending: Boolean = false, val queryGeneration: Long = 0, val documentGeneration: Long = 0,
    val bodyMatchesByLine: Map<Int, List<IndexedFindMatch>> = emptyMap(),
    val titleMatches: List<IndexedFindMatch> = emptyList(),
) {
    val active: FindMatch? get() = matches.getOrNull(activeIndex)
    fun move(delta: Int): FindState = if (pending || matches.isEmpty()) this else copy(activeIndex = Math.floorMod(activeIndex + delta, matches.size))
}

/** Each logical line receives only its intersecting matches, with indices into the full result list. */
fun groupBodyMatches(text: String, matches: List<FindMatch>, checkpoint: () -> Unit = {}): Map<Int, List<IndexedFindMatch>> {
    val starts = dev.securenotes.document.DocumentEdits.lineStarts(text)
    val grouped = mutableMapOf<Int, MutableList<IndexedFindMatch>>()
    matches.forEachIndexed { index, match ->
        checkpoint()
        if (match.kind == HitKind.BODY && match.end > match.start) {
            val first = starts.binarySearch(match.start).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
            val last = starts.binarySearch(match.end - 1).let { if (it >= 0) it else -it - 2 }.coerceAtMost(starts.lastIndex)
            for (line in first..last) {
                checkpoint()
                grouped.getOrPut(line) { mutableListOf() }.add(IndexedFindMatch(index, match))
            }
        }
    }
    return grouped.mapValues { it.value.toList() }
}

/** The visible title is trimmed; the placeholder has no source range to highlight. */
data class TitleWindow(val text: String, val offset: Int, val placeholder: Boolean) {
    fun visibleOffset(original: Int): Int = (original - offset).coerceIn(0, (text.length - 1).coerceAtLeast(0))
    fun clip(match: FindMatch): FindMatch? {
        if (placeholder || match.kind != HitKind.TITLE) return null
        val start = maxOf(match.start, offset); val end = minOf(match.end, offset + text.length)
        return if (end > start) match.copy(start = start - offset, end = end - offset) else null
    }
}
fun titleWindow(title: String): TitleWindow {
    val trimmed = title.trim()
    return TitleWindow(trimmed.ifEmpty { "Untitled" }, title.length - title.trimStart().length, trimmed.isEmpty())
}

/** Literal matching with a map from normalized UTF-16 units back to complete original characters. */
object FindText {
    /** Immutable preparation can be shared by workers; offsets always refer to [original]. */
    class Prepared internal constructor(val original: String, internal val text: String,
        private val starts: IntArray, private val ends: IntArray) {
        internal fun match(kind: HitKind, needle: String, result: MutableList<FindMatch>, checkpoint: () -> Unit) {
            var from = 0
            while (from <= text.length - needle.length) {
                checkpoint()
                val index = text.indexOf(needle, from)
                if (index < 0) break
                val match = FindMatch(kind, starts[index], ends[index + needle.length - 1])
                if (result.lastOrNull() != match) result.add(match)
                from = index + 1
            }
        }
    }
    private fun mark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
    }
    fun prepare(value: String, checkpoint: () -> Unit = {}): Prepared {
        val text = StringBuilder(); val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        var offset = 0
        while (offset < value.length) {
            checkpoint()
            val start = offset
            offset += Character.charCount(value.codePointAt(offset))
            while (offset < value.length && mark(value.codePointAt(offset))) {
                checkpoint(); offset += Character.charCount(value.codePointAt(offset))
            }
            val folded = Normalizer.normalize(value.substring(start, offset).uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFD)
            var i = 0
            while (i < folded.length) {
                val cp = folded.codePointAt(i); val length = Character.charCount(cp)
                if (!mark(cp)) {
                    text.appendCodePoint(cp)
                    repeat(length) { starts.add(start); ends.add(offset) }
                }
                i += length
            }
        }
        return Prepared(value, text.toString(), starts.toIntArray(), ends.toIntArray())
    }
    fun matches(title: String, body: String, query: String, checkpoint: () -> Unit = {}): List<FindMatch> =
        matches(prepare(title, checkpoint), prepare(body, checkpoint), query, checkpoint)

    fun matches(title: Prepared, body: Prepared, query: String, checkpoint: () -> Unit = {}): List<FindMatch> {
        val needle = prepare(query, checkpoint).text
        if (needle.isEmpty()) return emptyList()
        val result = ArrayList<FindMatch>()
        title.match(HitKind.TITLE, needle, result, checkpoint)
        body.match(HitKind.BODY, needle, result, checkpoint)
        return result
    }
}
