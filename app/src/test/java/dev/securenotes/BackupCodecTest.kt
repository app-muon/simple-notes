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
        assertThrows(IllegalArgumentException::class.java) { BackupService.validate(BackupManifest(version = 5, notes = listOf(note), attachments = emptyList(), order = listOf(note.id))) }
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
    private fun attachment(data: ByteArray, key: ByteArray, id: String): ByteArray =
        ByteArrayOutputStream().also { out -> AttachmentCodec.encrypt(out, key, id) { it.write(data) } }.toByteArray()
    private fun opened(bytes: ByteArray, key: ByteArray, id: String): ByteArray = AttachmentCodec.decrypt(bytes.inputStream(), key, id) { it.readBytes() }
    @Test fun `attachment files round trip around Tink segment boundaries`() {
        // AES256_GCM_HKDF_1MB: 1 MiB ciphertext segments, each ending in a 16-byte tag; the first also holds Tink's
        // header (length byte, 32-byte salt, 7-byte nonce prefix). SSNA adds its own 8 bytes before all of it.
        val segment = 1 shl 20; val tag = 16; val tinkHeader = 1 + 32 + 7
        val first = segment - tinkHeader - tag; val later = segment - tag
        assertEquals(1_048_520, first); assertEquals(1_048_560, later)
        val key = Crypto.newStreamingKey(); val id = newId()
        for (size in listOf(0, 1, first - 1, first, first + 1, first + later - 1, first + later, first + later + 1, first + 2 * later + 4321)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            val encoded = attachment(data, key, id)
            val segments = if (size <= first) 1 else 1 + (size - first + later - 1) / later
            assertEquals(size.toString(), 8 + tinkHeader + size + segments * tag, encoded.size)
            assertArrayEquals(data, opened(encoded, key, id))
        }
        // Fresh randomness: the same attachment never encrypts to the same file twice.
        assertFalse(attachment(byteArrayOf(1), key, id).contentEquals(attachment(byteArrayOf(1), key, id)))
    }
    @Test fun `truncated swapped tampered and wrong-key attachment files fail`() {
        val segment = 1 shl 20
        val key = Crypto.newStreamingKey(); val id = newId()
        val encoded = attachment(ByteArray(2 * segment + 1000) { (it % 13).toByte() }, key, id)
        // Cuts at segment boundaries leave a valid authenticated prefix; it must never pass as a complete file.
        for (length in listOf(8 + segment, 8 + 2 * segment, encoded.size - 1, encoded.size - 20, 8 + 40, 8, 3)) {
            assertThrows(length.toString(), Exception::class.java) { opened(encoded.copyOf(length), key, id) }
            assertThrows(length.toString(), Exception::class.java) { AttachmentCodec.decrypt(encoded.copyOf(length).inputStream(), key, id) { it.read() } }
        }
        assertThrows(Exception::class.java) { opened(encoded, key, newId()) }
        assertThrows(Exception::class.java) { opened(encoded, Crypto.newStreamingKey(), id) }
        assertThrows(Exception::class.java) { opened(encoded.clone().apply { this[segment] = (this[segment].toInt() xor 1).toByte() }, key, id) }
        assertThrows(Exception::class.java) { AttachmentCodec.decrypt(encoded.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }.inputStream(), key, id) { it.read() } }
        assertThrows(IllegalArgumentException::class.java) { opened(encoded.clone().apply { this[0] = 0 }, key, id) }
        assertThrows(IllegalArgumentException::class.java) { opened(encoded.clone().apply { this[7] = 2 }, key, id) }
        assertThrows(IllegalArgumentException::class.java) { attachment(byteArrayOf(1), key, "not-an-id") }
        assertThrows(IllegalArgumentException::class.java) { opened(encoded, key, id.uppercase()) }
    }
    @Suppress("DEPRECATION")
    private fun keyset(handle: com.google.crypto.tink.KeysetHandle): ByteArray =
        ByteArrayOutputStream().also { com.google.crypto.tink.CleartextKeysetHandle.write(handle, com.google.crypto.tink.BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
    @Test fun `attachment keys must be the exact padded encoding of one AES256_GCM_HKDF_1MB key`() {
        val key = Crypto.newStreamingKey()
        val encoded = BackupCodec.encodeKey(key)
        assertArrayEquals(key, BackupCodec.decodeKey(encoded))
        assertFalse(encoded.any { it.isWhitespace() })
        val invalid = mutableListOf(encoded + "\n", " $encoded", encoded.chunked(16).joinToString("\r\n"), BackupCodec.encodeKey(ByteArray(4097) { 1 }), BackupCodec.encodeKey(byteArrayOf(1, 2, 3)), "%%%")
        if (encoded.endsWith('=')) invalid += encoded.trimEnd('=')
        val templates = com.google.crypto.tink.KeyTemplates.get("AES256_GCM_HKDF_1MB").toParameters()
        invalid += BackupCodec.encodeKey(keyset(com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("AES128_GCM_HKDF_4KB"))))
        invalid += BackupCodec.encodeKey(keyset(com.google.crypto.tink.KeysetHandle.newBuilder()
            .addEntry(com.google.crypto.tink.KeysetHandle.generateEntryFromParameters(templates).withRandomId().makePrimary())
            .addEntry(com.google.crypto.tink.KeysetHandle.generateEntryFromParameters(templates).withRandomId()).build()))
        for (value in invalid) assertThrows(value.take(40), Exception::class.java) { BackupCodec.decodeKey(value) }
    }
    @Test fun `version 4 indexes name exactly their attachments with safe unique names`() {
        val id = newId(); val second = newId()
        val note = Note(title = "N", document = Document(attachments = listOf(id)))
        val row = AttachmentRow(id, note.id, "x", "text/plain", 1, "a".repeat(64))
        val key = BackupCodec.encodeKey(Crypto.newStreamingKey())
        val valid = BackupManifest(version = 4, notes = listOf(note), attachments = listOf(row), order = listOf(note.id),
            attachmentKey = key, attachmentFolder = "Notes-x-attachments", attachmentFiles = mapOf(id to "$id.ssna"))
        BackupService.validate(valid)
        BackupService.validate(valid.copy(attachmentFiles = mapOf(id to "$id.ssna (1)")))
        val bare = Note(title = "Text only")
        BackupService.validate(BackupManifest(version = 4, notes = listOf(bare), attachments = emptyList(), order = listOf(bare.id), attachmentKey = key, attachmentFiles = emptyMap()))
        val both = note.copy(document = Document(attachments = listOf(id, second)))
        for (invalid in listOf(
            valid.copy(attachmentKey = null), valid.copy(attachmentKey = ""), valid.copy(attachmentFiles = null), valid.copy(attachmentFolder = null),
            valid.copy(attachmentFiles = emptyMap()), valid.copy(attachmentFiles = mapOf(id to "a.ssna", second to "b.ssna")),
            valid.copy(attachmentFiles = mapOf(id to "../$id.ssna")), valid.copy(attachmentFiles = mapOf(id to "dir/$id")),
            valid.copy(attachmentFiles = mapOf(id to "dir\\$id")), valid.copy(attachmentFiles = mapOf(id to "..")),
            valid.copy(attachmentFiles = mapOf(id to " ")), valid.copy(attachmentFiles = mapOf(id to "a".repeat(256))),
            valid.copy(attachmentFolder = "a/b"), valid.copy(attachmentFolder = "."), valid.copy(attachmentFolder = ""),
            valid.copy(notes = listOf(both), attachments = listOf(row, row.copy(id = second)), attachmentFiles = mapOf(id to "A.ssna", second to "a.ssna")),
            valid.copy(version = 3), valid.copy(version = 3, attachmentFolder = null, attachmentFiles = null),
        )) assertThrows(invalid.toString().take(60), IllegalArgumentException::class.java) { BackupService.validate(invalid) }
    }
    @Test fun `the manifest limit applies to the complete version 4 index`() {
        val key = Crypto.newStreamingKey()
        val note = Note(title = "")
        val content = BackupManifest(notes = listOf(note), attachments = emptyList(), order = listOf(note.id))
        val contentSize = documentJson.encodeToString(BackupManifest.serializer(), content).toByteArray().size
        val added = BackupService.indexBytes(content, key, null, emptyMap(), 7).size - contentSize
        assertTrue(added > 0)
        val fits = BackupService.MANIFEST_LIMIT - contentSize - added
        val atLimit = content.copy(notes = listOf(note.copy(title = "x".repeat(fits))))
        assertEquals(BackupService.MANIFEST_LIMIT, BackupService.indexBytes(atLimit, key, null, emptyMap(), 7).size)
        // One byte more: the content alone is still under the limit, but its complete index is not.
        val over = content.copy(notes = listOf(note.copy(title = "x".repeat(fits + 1))))
        BackupService.checkManifestSize(documentJson.encodeToString(BackupManifest.serializer(), over).toByteArray().size, 7)
        val failure = assertThrows(ManifestTooLarge::class.java) { BackupService.indexBytes(over, key, null, emptyMap(), 7) }
        assertEquals(7L, failure.revision)
        // Version-4 fields never appear in a version-3 manifest, so its bytes and fingerprint are unchanged.
        val json = documentJson.encodeToString(BackupManifest.serializer(), content)
        assertTrue(listOf("attachmentKey", "attachmentFolder", "attachmentFiles").none { it in json })
    }
    @Test fun `local authenticated envelope binds associated data`() {
        val key = Crypto.random(32); val data = "secret".toByteArray(); val aad = "generation".toByteArray()
        val encrypted = Crypto.seal(key, data, aad)
        assertArrayEquals(data, Crypto.open(key, encrypted, aad))
        assertThrows(Exception::class.java) { Crypto.open(key, encrypted, "another".toByteArray()) }
    }
}
