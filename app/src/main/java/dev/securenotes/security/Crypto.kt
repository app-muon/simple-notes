package dev.securenotes.security

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    const val ITERATIONS = 600_000
    private val random = SecureRandom()
    init { StreamingAeadConfig.register() }
    fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(iterations in ITERATIONS..2_000_000)
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun subkey(root: ByteArray, label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(root, "HmacSHA256")); doFinal(label.toByteArray(Charsets.UTF_8))
    }
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = random(12)
        return nonce + Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad); doFinal(plaintext)
        }
    }
    fun open(key: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        require(ciphertext.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            updateAAD(aad); doFinal(ciphertext, 12, ciphertext.size - 12)
        }
    }
    @Suppress("DEPRECATION")
    fun newStreamingKey(): ByteArray = ByteArrayOutputStream().use { out ->
        CleartextKeysetHandle.write(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB")), BinaryKeysetWriter.withOutputStream(out))
        out.toByteArray()
    }
    @Suppress("DEPRECATION")
    fun streaming(keyset: ByteArray): StreamingAead = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(keyset)).getPrimitive(StreamingAead::class.java)
    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
