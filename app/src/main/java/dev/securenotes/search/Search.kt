package dev.securenotes.search

import java.text.Normalizer
import java.util.Locale

data class Token(val term: String, val start: Int, val end: Int)
data class SearchHit(
    val noteId: String, val title: String, val blockId: String?, val attachmentId: String?,
    val start: Int, val end: Int, val snippet: String, val context: String?, val score: Int,
)
object SearchText {
    /** Soft chunk limit: finish the current word so indexing never loses a boundary-spanning token. */
    fun readChunk(reader: java.io.Reader, size: Int = 8192): String {
        require(size > 0)
        val chars = CharArray(size)
        var count = 0
        while (count < size) {
            val read = reader.read(chars, count, size - count)
            if (read < 0) break
            count += read
        }
        val result = StringBuilder().append(chars, 0, count)
        while (result.isNotEmpty() && isWordPart(result.last())) {
            val next = reader.read()
            if (next < 0) break
            result.append(next.toChar())
        }
        return result.toString()
    }
    fun chunks(text: String): Sequence<String> = sequence {
        text.reader().use { reader ->
            while (true) {
                val chunk = readChunk(reader)
                if (chunk.isEmpty()) break
                yield(chunk)
            }
        }
    }
    private fun isWordPart(char: Char): Boolean = char.isLetterOrDigit() || char.isSurrogate() ||
        Character.getType(char) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    private val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    fun tokens(value: String): List<Token> = words.findAll(value).map { Token(normalize(it.value), it.range.first, it.range.last + 1) }.toList()
    /** A single insertion, deletion, substitution or adjacent transposition. */
    fun oneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        if (i == minOf(a.length, b.length)) return true
        return when {
            a.length < b.length -> a.substring(i) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            a.substring(i + 1) == b.substring(i + 1) -> true
            i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] -> a.substring(i + 2) == b.substring(i + 2)
            else -> false
        }
    }
    fun score(query: String, candidate: String): Int = when {
        query == candidate -> 30
        candidate.startsWith(query) -> 20
        query.length >= 4 && oneEdit(query, candidate) -> 10
        else -> 0
    }
    fun snippet(text: String, start: Int, end: Int): String {
        val left = (start - 45).coerceAtLeast(0)
        val right = (end + 70).coerceAtMost(text.length)
        return (if (left > 0) "…" else "") + text.substring(left, right).replace('\n', ' ') + if (right < text.length) "…" else ""
    }
}
