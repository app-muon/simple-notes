package dev.securenotes

import dev.securenotes.backup.*
import dev.securenotes.document.*
import dev.securenotes.security.Crypto
import dev.securenotes.storage.AttachmentRow
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    @Test fun `preflight rejects random truncated and unsupported files without a password`() {
        assertThrows(Exception::class.java) { BackupCodec.validateHeader("not a backup".byteInputStream()) }
        val encoded = encrypted("content".toByteArray())
        BackupCodec.validateHeader(encoded.inputStream())
        assertThrows(Exception::class.java) { BackupCodec.validateHeader(encoded.copyOf(40).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validateHeader(encoded.clone().apply { this[7] = 2 }.inputStream()) }
        assertThrows(BackupPasswordException::class.java) { decrypted(encoded, "incorrect") }
    }
    private fun encrypted(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> BackupCodec.encrypt(out, "correct passphrase".toCharArray()) { it.write(bytes) } }.toByteArray()
    private fun decrypted(bytes: ByteArray, password: String = "correct passphrase"): ByteArray = BackupCodec.decrypt(bytes.inputStream(), password.toCharArray()) { it.readBytes() }
    @Test fun `password encrypted stream roundtrips across segment boundaries`() {
        val data = ByteArray(2_100_123) { (it % 239).toByte() }
        val encoded = encrypted(data)
        assertFalse(encoded.contentEquals(data)); assertArrayEquals(data, decrypted(encoded))
        assertFalse(encoded.contentEquals(encrypted(data)))
    }
    @Test fun `wrong password altered header truncated or corrupted payload all fail`() {
        val encoded = encrypted("Private title and attachment".toByteArray())
        assertThrows(Exception::class.java) { decrypted(encoded, "wrong password") }
        assertThrows(Exception::class.java) { decrypted(encoded.copyOf(encoded.size - 1)) }
        assertThrows(Exception::class.java) { decrypted(encoded.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }) }
        assertThrows(Exception::class.java) { decrypted(encoded.clone().apply { this[15] = (this[15].toInt() xor 1).toByte() }) }
    }
    @Test fun `unsupported version and excessive KDF work are rejected before derivation`() {
        val encoded = encrypted(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { decrypted(encoded.clone().apply { this[7] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) { decrypted(encoded.clone().apply { this[8] = 127 }) }
    }
    @Test fun `authentication completes even when consumer reads only first byte`() {
        val encoded = encrypted(ByteArray(2_100_000))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { BackupCodec.decrypt(encoded.inputStream(), "correct passphrase".toCharArray()) { it.read() } }
    }
    @Test fun `manifest rejects foreign references duplicates paths and unknown versions`() {
        val note = Note(title = "Hello")
        BackupService.validate(BackupManifest(version = 1, notes = listOf(note), attachments = emptyList(), sort = SortOrder.EDITED))
        BackupService.validate(BackupManifest(notes = listOf(note), attachments = emptyList(), order = listOf(note.id)))
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(version = 1, notes = listOf(note, note), attachments = emptyList(), sort = SortOrder.EDITED)) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(version = 3, notes = listOf(note), attachments = emptyList(), sort = SortOrder.EDITED)) }
        val id = newId()
        val attached = note.copy(document = Document(attachments = listOf(id)))
        val owned = AttachmentRow(id, attached.id, "x", "text/plain", 1, "a".repeat(64))
        BackupService.validate(BackupManifest(notes = listOf(attached), attachments = listOf(owned), order = listOf(attached.id)))
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(version = 1, notes = listOf(attached), attachments = listOf(owned.copy(noteId = newId())), sort = SortOrder.EDITED)) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(notes = listOf(note), attachments = listOf(owned), order = listOf(note.id))) }
        val other = Note(title = "Other", document = Document(attachments = listOf(id)))
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(notes = listOf(attached, other), attachments = listOf(owned), order = listOf(attached.id, other.id))) }
    }
    @Test fun `manual backup order must contain every note exactly once`() {
        val a = Note(title = "A"); val b = Note(title = "B")
        val valid = BackupManifest(notes = listOf(a, b), attachments = emptyList(), order = listOf(b.id, a.id))
        BackupService.validate(valid); assertEquals(listOf(b.id, a.id), valid.orderedIds())
        for (order in listOf(null, emptyList(), listOf(a.id), listOf(a.id, a.id), listOf(a.id, newId()))) {
            assertThrows(IllegalArgumentException::class.java) { BackupService.validate(valid.copy(order = order)) }
        }
    }
    @Test fun `local authenticated envelope binds associated data`() {
        val key = Crypto.random(32); val data = "secret".toByteArray(); val aad = "generation".toByteArray()
        val encrypted = Crypto.seal(key, data, aad)
        assertArrayEquals(data, Crypto.open(key, encrypted, aad))
        assertThrows(Exception::class.java) { Crypto.open(key, encrypted, "another".toByteArray()) }
    }
}
