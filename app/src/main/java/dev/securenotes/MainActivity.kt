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
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val app get() = application as NotesApplication
    private lateinit var model: NotesViewModel
    private lateinit var prompt: BiometricPrompt
    private var launchedExternal = false
    private var externalWasPaused = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[NotesViewModel::class.java]
        // Reattach callbacks even during activity recreation while a prompt is already running.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                app.scope.launch {
                    try { app.unlock(app.keys.complete(checkNotNull(result.cryptoObject?.cipher)), app.authenticationEpoch) }
                    catch (_: kotlinx.coroutines.CancellationException) { }
                    catch (e: Exception) {
                        android.util.Log.e("SecureNotes", "Vault unlock failed: ${e.javaClass.name}\n${e.stackTrace.joinToString("\n")}")
                        model.error = "The vault could not be opened. Existing encrypted data has been preserved."
                    } finally { app.endAuthentication() }
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                app.endAuthentication()
                if (errorCode !in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON))
                    model.error = "Authentication is unavailable. Try again using your device credential."
            }
        })
        if (savedInstanceState == null) receive(intent)
        setContent { SecureNotesApp(model, ::authenticate, ::configureLock, ::openAttachment) }
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
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        if (!app.beginAuthentication()) return
        try {
            val cipher = app.keys.cipher()
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Secure Notes")
                .setSubtitle("Use your fingerprint, face, or device credential")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build(), BiometricPrompt.CryptoObject(cipher))
        } catch (_: Exception) { app.endAuthentication(); model.error = "The device encryption key is unavailable. Your encrypted files have been preserved. Restore a backup after resetting the app if necessary." }
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
