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
        require(ByteArray(4).also(data::readFully).contentEquals(magic)) { "Not a Secure Notes backup" }
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
