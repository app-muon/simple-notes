package dev.securenotes.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import dev.securenotes.storage.atomicWrite
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore key is gone or invalidated while its wrapped root remains; only the recovery passphrase can open the vault. */
class DeviceKeyUnavailableException(cause: Throwable? = null) : Exception("Device key unavailable", cause)

class DeviceKeys(context: Context) {
    private val file = File(context.noBackupFilesDir, "vault.key")
    private val alias = "secure-notes-vault-v1"
    private val aad = "SecureNotes device root v1".toByteArray()
    private val resumeAlias = "secure-notes-resume-v1"
    private val resumeAad = "SecureNotes in-memory resume v1".toByteArray()
    val existing get() = file.exists()
    fun cipher(): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (existing) throw DeviceKeyUnavailableException()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setUnlockedDeviceRequired(true).setInvalidatedByBiometricEnrollment(false).build())
            }.generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            val key = store.getKey(alias, null) as SecretKey
            try {
                if (existing) init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, file.readBytes().copyOfRange(0, 12)))
                else init(Cipher.ENCRYPT_MODE, key)
            } catch (e: KeyPermanentlyInvalidatedException) { throw DeviceKeyUnavailableException(e) }
        }
    }
    /** Returns the vault root. When creating the envelope, wraps [root] (passphrase recovery) or a new random root. */
    fun complete(cipher: Cipher, root: ByteArray? = null): ByteArray {
        // Even AAD updates use the auth-per-use key. Submit them only after BiometricPrompt succeeds.
        cipher.updateAAD(aad)
        return if (existing) {
            val data = file.readBytes(); cipher.doFinal(data, 12, data.size - 12)
        } else {
            (root?.copyOf() ?: Crypto.random(32)).also { atomicWrite(file, cipher.iv + cipher.doFinal(it)) }
        }
    }
    /** Discards the unusable device envelope before re-enrolling a root recovered from the passphrase. */
    fun reset() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        file.delete()
    }
    private fun resumeKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(resumeAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(resumeAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUnlockedDeviceRequired(true).build())
            }.generateKey()
        }
        return store.getKey(resumeAlias, null) as SecretKey
    }
    fun sealResume(root: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, resumeKey()); updateAAD(resumeAad)
        iv + doFinal(root)
    }
    fun openResume(token: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(token.size >= 28)
        init(Cipher.DECRYPT_MODE, resumeKey(), GCMParameterSpec(128, token.copyOfRange(0, 12)))
        updateAAD(resumeAad); doFinal(token, 12, token.size - 12)
    }
}
