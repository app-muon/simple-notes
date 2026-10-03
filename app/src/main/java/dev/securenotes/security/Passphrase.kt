package dev.securenotes.security

import android.content.Context
import dev.securenotes.R
import java.security.SecureRandom

/** App-generated recovery passphrase: EFF large wordlist, 8 words, ~103 bits. Also the backup password. */
object Passphrase {
    const val WORDS = 8
    private const val SALT = 32
    private val random = SecureRandom()
    @Volatile private var cached: List<String>? = null
    fun wordlist(context: Context): List<String> = cached ?: context.resources.openRawResource(R.raw.eff_large_wordlist)
        .bufferedReader(Charsets.UTF_8).useLines { lines -> lines.map(String::trim).filter(String::isNotEmpty).toList() }
        .also { check(it.size == 7776 && it.toSet().size == it.size) { "Damaged wordlist" }; cached = it }
    fun generate(words: List<String>, count: Int = WORDS): String = List(count) { words[random.nextInt(words.size)] }.joinToString(" ")
    /** Canonical form: lowercase words separated by single spaces. Hyphens are part of some list words. */
    fun normalize(input: String): String = input.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
    fun words(input: String): List<String> = normalize(input).split(' ').filter(String::isNotEmpty)
    fun unknownWords(input: String, words: Set<String>): List<String> = words(input).filter { it !in words }
    fun isComplete(input: String, words: Set<String>): Boolean = words(input).let { it.size == WORDS && it.all(words::contains) }
    fun recoveryAad(generation: String): ByteArray = "SecureNotes recovery v1:$generation".toByteArray()
    /** salt | AES-GCM(PBKDF2(passphrase, salt), root). */
    fun wrap(root: ByteArray, passphrase: CharArray, aad: ByteArray): ByteArray {
        val salt = Crypto.random(SALT)
        val key = Crypto.derive(passphrase, salt)
        return try { salt + Crypto.seal(key, root, aad) } finally { key.fill(0) }
    }
    fun unwrap(wrapped: ByteArray, passphrase: CharArray, aad: ByteArray): ByteArray {
        require(wrapped.size >= SALT + 28) { "Damaged recovery key" }
        val key = Crypto.derive(passphrase, wrapped.copyOfRange(0, SALT))
        return try { Crypto.open(key, wrapped.copyOfRange(SALT, wrapped.size), aad) } finally { key.fill(0) }
    }
}
