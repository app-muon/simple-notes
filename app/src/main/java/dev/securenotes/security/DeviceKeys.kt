package dev.securenotes.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.securenotes.storage.atomicWrite
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeys(context: Context) {
    private val file = File(context.noBackupFilesDir, "vault.key")
    private val alias = "secure-notes-vault-v1"
    private val aad = "SecureNotes device root v1".toByteArray()
    val existing get() = file.exists()
    fun cipher(): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            check(!existing) { "Device key unavailable" }
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
            if (existing) init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, file.readBytes().copyOfRange(0, 12)))
            else init(Cipher.ENCRYPT_MODE, key)
        }
    }
    fun complete(cipher: Cipher): ByteArray {
        // Even AAD updates use the auth-per-use key. Submit them only after BiometricPrompt succeeds.
        cipher.updateAAD(aad)
        return if (existing) {
            val data = file.readBytes(); cipher.doFinal(data, 12, data.size - 12)
        } else {
            Crypto.random(32).also { root -> atomicWrite(file, cipher.iv + cipher.doFinal(root)) }
        }
    }
}
