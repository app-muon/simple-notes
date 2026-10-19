package dev.securenotes.backup

import dev.securenotes.security.Crypto
import java.io.*

class BackupPasswordException : java.security.GeneralSecurityException("Incorrect password or damaged backup")

/** Versioned envelope; Tink defines the streaming ciphertext, never our own cipher. */
object BackupCodec {
    private val magic = byteArrayOf(0x53, 0x53, 0x4e, 0x42)
    private const val VERSION = 1
    private fun prefix(salt: ByteArray, iterations: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply { write(magic); writeInt(VERSION); writeInt(iterations); write(salt) }
    }.toByteArray()
    fun encrypt(output: OutputStream, password: CharArray, write: (OutputStream) -> Unit) {
        val salt = Crypto.random(32)
        val prefix = prefix(salt, Crypto.ITERATIONS)
        val key = Crypto.derive(password, salt)
        val keyset = Crypto.newStreamingKey()
        try {
            val sealed = Crypto.seal(key, keyset, prefix)
            val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply {
                write(prefix); writeInt(sealed.size); write(sealed)
            } }.toByteArray()
            output.write(header)
            Crypto.streaming(keyset).newEncryptingStream(output, header).use(write)
        } finally { key.fill(0); keyset.fill(0) }
    }
    private data class Header(val iterations: Int, val salt: ByteArray, val sealed: ByteArray, val prefix: ByteArray, val bytes: ByteArray)
    private fun header(data: DataInputStream): Header {
        require(ByteArray(4).also(data::readFully).contentEquals(magic)) { "Not a Notes backup" }
        require(data.readInt() == VERSION) { "Unsupported backup version" }
        val iterations = data.readInt()
        require(iterations in Crypto.ITERATIONS..2_000_000) { "Unsupported KDF parameters" }
        val salt = ByteArray(32).also(data::readFully)
        val size = data.readInt()
        require(size in 28..4096) { "Invalid key envelope" }
        val sealed = ByteArray(size).also(data::readFully)
        val prefix = prefix(salt, iterations)
        val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply { write(prefix); writeInt(size); write(sealed) } }.toByteArray()
        return Header(iterations, salt, sealed, prefix, header)
    }
    /** Structural preflight only. Authenticity still requires the password and full stream validation. */
    fun validateHeader(input: InputStream) {
        val data = DataInputStream(input)
        header(data)
        require(data.read() != -1) { "Missing encrypted payload" }
    }
    /** The base64 form a version-4 manifest stores: standard alphabet, padded, no line breaks. */
    fun encodeKey(keyset: ByteArray): String = java.util.Base64.getEncoder().encodeToString(keyset)
    /** Decodes and checks a keyset read from an authenticated index; anything but [encodeKey]'s exact output is rejected. */
    fun decodeKey(encoded: String): ByteArray {
        require(encoded.length <= 8192) { "Invalid attachment key" }
        val keyset = java.util.Base64.getDecoder().decode(encoded)
        try {
            require(encodeKey(keyset) == encoded) { "Invalid attachment key" }
            Crypto.checkStreamingKey(keyset)
            return keyset
        } catch (e: Exception) { keyset.fill(0); throw e }
    }
    fun <T> decrypt(input: InputStream, password: CharArray, read: (InputStream) -> T): T {
        val data = DataInputStream(input)
        val header = header(data)
        val key = Crypto.derive(password, header.salt, header.iterations)
        val keyset = try { Crypto.open(key, header.sealed, header.prefix) }
        catch (_: java.security.GeneralSecurityException) { throw BackupPasswordException() }
        finally { key.fill(0) }
        try {
            return Crypto.streaming(keyset).newDecryptingStream(data, header.bytes).use { clear ->
                val result = read(clear)
                // ZIP parsers can stop before the authenticated final segment. Always drain it.
                val buffer = ByteArray(64 * 1024)
                while (clear.read(buffer) != -1) Unit
                buffer.fill(0)
                result
            }
        } finally { keyset.fill(0) }
    }
}

/**
 * One attachment of a version-4 backup: ASCII `SSNA`, version 1, then Tink streaming ciphertext under the pair's
 * attachment key. The associated data binds the file to its attachment ID, never to its display name.
 */
object AttachmentCodec {
    private val magic = byteArrayOf(0x53, 0x53, 0x4e, 0x41)
    private const val VERSION = 1
    private val header = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).apply { write(magic); writeInt(VERSION) } }.toByteArray()
    private fun associatedData(id: String): ByteArray {
        require(dev.securenotes.document.validId(id)) { "Invalid attachment ID" }
        return header + id.toByteArray(Charsets.UTF_8)
    }
    fun encrypt(output: OutputStream, keyset: ByteArray, id: String, write: (OutputStream) -> Unit) {
        val associated = associatedData(id)
        output.write(header)
        Crypto.streaming(keyset).newEncryptingStream(output, associated).use(write)
    }
    fun <T> decrypt(input: InputStream, keyset: ByteArray, id: String, read: (InputStream) -> T): T {
        val associated = associatedData(id)
        val data = DataInputStream(input)
        require(ByteArray(4).also(data::readFully).contentEquals(magic)) { "Not a Notes attachment" }
        require(data.readInt() == VERSION) { "Unsupported attachment version" }
        return Crypto.streaming(keyset).newDecryptingStream(data, associated).use { clear ->
            val result = read(clear)
            // As for backups: a reader that stops early must not skip the authenticated final segment.
            val buffer = ByteArray(64 * 1024)
            while (clear.read(buffer) != -1) Unit
            buffer.fill(0)
            result
        }
    }
}
