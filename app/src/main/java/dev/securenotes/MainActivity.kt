package dev.securenotes

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.securenotes.ui.NotesViewModel
import dev.securenotes.ui.SecureNotesApp
import dev.securenotes.ui.SharedContent
import dev.securenotes.security.DeviceKeyUnavailableException
import dev.securenotes.security.Passphrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val app get() = application as NotesApplication
    private lateinit var model: NotesViewModel
    private lateinit var prompt: BiometricPrompt
    private var launchedExternal = false
    private var externalWasPaused = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Task snapshots are stored by the system outside the vault; user-initiated screenshots remain allowed.
        setRecentsScreenshotEnabled(false)
        model = ViewModelProvider(this)[NotesViewModel::class.java]
        // Reattach callbacks even during activity recreation while a prompt is already running.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (model.revealing) {
                    model.revealing = false
                    lifecycleScope.launch {
                        // Completing the vault-key operation proves a fresh authentication; the unwrapped root is discarded.
                        try { withContext(Dispatchers.IO) { app.keys.complete(checkNotNull(result.cryptoObject?.cipher)).fill(0) }; model.revealPassphrase() }
                        catch (_: Exception) { model.error = "Authentication is unavailable. Try again using your device credential." }
                    }
                    return
                }
                app.scope.launch {
                    try {
                        val recovered = app.pendingRecovery
                        // A new envelope over an existing vault is only valid for a root recovered from the passphrase.
                        if (recovered == null && !app.keys.existing && app.repository.hasVault()) throw DeviceKeyUnavailableException()
                        app.unlock(app.keys.complete(checkNotNull(result.cryptoObject?.cipher), recovered), app.authenticationEpoch)
                    }
                    catch (_: kotlinx.coroutines.CancellationException) { }
                    catch (_: DeviceKeyUnavailableException) { model.keyUnavailable = true }
                    catch (e: Exception) {
                        android.util.Log.e("SecureNotes", "Vault unlock failed: ${e.javaClass.name}")
                        model.error = "The vault could not be opened. Existing encrypted data has been preserved."
                    } finally { app.clearRecovery(); app.endAuthentication() }
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (model.revealing) { model.revealing = false; return }
                app.clearRecovery(); app.endAuthentication()
                if (errorCode !in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON))
                    model.error = "Authentication is unavailable. Try again using your device credential."
            }
        })
        if (savedInstanceState == null) receive(intent)
        setContent { SecureNotesApp(model, ::authenticate, ::configureLock, ::openAttachment, ::recover, ::revealPassphrase) }
    }
    override fun onResume() {
        super.onResume()
        app.checkDevice()
        if (launchedExternal && externalWasPaused) { app.revokeGrants(); launchedExternal = false; externalWasPaused = false }
    }
    override fun onPause() { app.flushEdits(); if (launchedExternal) externalWasPaused = true; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) uris += intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
        intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(uris::add) } }
        model.receive(SharedContent(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), uris.filter { it.scheme == "content" }.distinct(), intent.type))
        intent.action = null // An activity recreation must not append the same share twice.
    }
    private fun configureLock() { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    private fun authenticate() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked) return
        if (!app.beginAuthentication()) return
        lifecycleScope.launch { try {
            if (app.tryResume(app.authenticationEpoch)) { app.endAuthentication(); return@launch }
            if (!app.authenticationIsCurrent() || keyguard.isDeviceLocked) { app.endAuthentication(); return@launch }
            if (!app.keys.existing && app.repository.hasVault()) throw DeviceKeyUnavailableException()
            val cipher = try { app.keys.cipher() } catch (e: DeviceKeyUnavailableException) {
                // Nothing is encrypted under an orphaned envelope without a vault; start the device key again.
                if (app.repository.hasVault()) throw e
                withContext(Dispatchers.IO) { app.keys.reset() }; app.keys.cipher()
            }
            prompt.authenticate(unlockPrompt(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: kotlinx.coroutines.CancellationException) { app.endAuthentication() }
        catch (_: DeviceKeyUnavailableException) { app.endAuthentication(); model.keyUnavailable = true }
        catch (_: Exception) { app.endAuthentication(); model.error = "The device encryption key is unavailable. Your encrypted files have been preserved. Use your recovery passphrase or restore a backup." } }
    }
    private fun unlockPrompt() = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock ${getString(R.string.app_name)}")
        .setSubtitle("Use your fingerprint, face, or device credential")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
    /** Unwraps the root with the recovery passphrase, then binds it to a new device key behind a fresh authentication. */
    private fun recover(passphrase: CharArray) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure || keyguard.isDeviceLocked || !app.beginAuthentication()) { passphrase.fill('\u0000'); return }
        lifecycleScope.launch {
            // Only the passphrase unwrap can mean "wrong passphrase"; key-store failures afterwards must not blame the words.
            val root = try {
                val canonical = Passphrase.normalize(String(passphrase)).toCharArray()
                try { withContext(Dispatchers.IO) { app.repository.recoverRoot(canonical) } } finally { canonical.fill('\u0000') }
            } catch (e: Exception) {
                app.endAuthentication()
                if (e is kotlinx.coroutines.CancellationException) throw e
                model.error = if (e is java.security.GeneralSecurityException) "That recovery passphrase is not correct."
                    else "The recovery key could not be read. Your encrypted notes have been preserved."
                return@launch
            } finally { passphrase.fill('\u0000') }
            try {
                app.beginRecovery(root)
                withContext(Dispatchers.IO) { app.keys.reset() }
                prompt.authenticate(unlockPrompt(), BiometricPrompt.CryptoObject(app.keys.cipher()))
            } catch (e: Exception) {
                app.clearRecovery(); app.endAuthentication(); model.keyUnavailable = true
                if (e is kotlinx.coroutines.CancellationException) throw e
                model.error = "Your passphrase is correct, but this phone could not create a new unlock key. Your notes are unchanged. Try again, or restart the phone first."
            }
        }
    }
    private fun revealPassphrase() {
        try {
            val cipher = app.keys.cipher()
            model.revealing = true
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Show recovery passphrase")
                .setSubtitle("Confirm it's you").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { model.revealing = false; model.error = "Authentication is unavailable. Try again using your device credential." }
    }
    private fun openAttachment(id: String) {
        val attachment = model.attachments[id] ?: return
        val uri = Uri.parse("content://$packageName.attachments/$id")
        try {
            app.checkAccess(); app.grants.add(uri)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = android.content.ClipData.newRawUri("Attachment", uri)
            launchedExternal = true; externalWasPaused = false
            startActivity(view)
        } catch (_: Exception) { app.revokeGrants(); launchedExternal = false; model.error = "No installed application could open this attachment." }
    }
}
