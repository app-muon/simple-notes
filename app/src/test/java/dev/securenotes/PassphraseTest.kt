package dev.securenotes

import dev.securenotes.backup.BackupCodec
import dev.securenotes.backup.BackupPasswordException
import dev.securenotes.security.Crypto
import dev.securenotes.security.Passphrase
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PassphraseTest {
    private val list = File("src/main/res/raw/eff_large_wordlist.txt").readLines().map(String::trim).filter(String::isNotEmpty)
    private val words = list.toSet()

    @Test fun `bundled wordlist is the complete EFF large list`() {
        assertEquals(7776, list.size); assertEquals(7776, words.size)
        assertTrue(list.all { it.matches(Regex("[a-z-]+")) })
    }
    @Test fun `generated passphrases have eight listed words and differ`() {
        val phrases = List(50) { Passphrase.generate(list) }
        phrases.forEach { phrase ->
            assertEquals(Passphrase.WORDS, phrase.split(' ').size)
            assertTrue(Passphrase.isComplete(phrase, words))
            assertEquals(phrase, Passphrase.normalize(phrase))
        }
        assertEquals(phrases.size, phrases.toSet().size)
    }
    @Test fun `normalization ignores case and spacing but keeps hyphenated list words`() {
        val hyphenated = list.first { '-' in it }
        assertTrue(Passphrase.isComplete("  Abacus\tabdomen\n${hyphenated.uppercase()}  abacus abacus abacus abacus abacus ", words))
        assertEquals("abacus $hyphenated", Passphrase.normalize(" ABACUS   $hyphenated "))
        assertEquals(listOf("abacuss"), Passphrase.unknownWords("abacuss abdomen", words))
        assertFalse(Passphrase.isComplete(List(7) { "abacus" }.joinToString(" "), words))
        assertFalse(Passphrase.isComplete(List(9) { "abacus" }.joinToString(" "), words))
    }
    @Test fun `recovery wrap opens only with the same passphrase and generation`() {
        val root = Crypto.random(32); val phrase = Passphrase.generate(list); val aad = Passphrase.recoveryAad("generation")
        val wrapped = Passphrase.wrap(root, phrase.toCharArray(), aad)
        assertArrayEquals(root, Passphrase.unwrap(wrapped, phrase.toCharArray(), aad))
        assertThrows(Exception::class.java) { Passphrase.unwrap(wrapped, Passphrase.generate(list).toCharArray(), aad) }
        assertThrows(Exception::class.java) { Passphrase.unwrap(wrapped, phrase.toCharArray(), Passphrase.recoveryAad("other")) }
        assertThrows(Exception::class.java) { Passphrase.unwrap(wrapped.copyOf(40), phrase.toCharArray(), aad) }
    }
    @Test fun `backups encrypted under a passphrase need that exact canonical passphrase`() {
        val phrase = Passphrase.generate(list)
        val encoded = ByteArrayOutputStream().also { out -> BackupCodec.encrypt(out, phrase.toCharArray()) { it.write("notes".toByteArray()) } }.toByteArray()
        assertArrayEquals("notes".toByteArray(), BackupCodec.decrypt(encoded.inputStream(), Passphrase.normalize(phrase.uppercase()).toCharArray()) { it.readBytes() })
        assertThrows(BackupPasswordException::class.java) { BackupCodec.decrypt(encoded.inputStream(), Passphrase.generate(list).toCharArray()) { it.readBytes() } }
    }
}
