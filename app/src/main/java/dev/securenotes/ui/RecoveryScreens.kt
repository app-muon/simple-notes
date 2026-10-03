@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.securenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.securenotes.security.Passphrase
import java.security.SecureRandom

private val secureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
private val wordKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

/** Blocks screenshots and screen recording while recovery words are visible. Dialogs set their own secure policy. */
@Composable private fun SecureWindow() {
    val window = androidx.activity.compose.LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * Copies the passphrase for pasting into a password manager. The clip is marked sensitive, so Android hides it in
 * the clipboard preview and keyboard suggestions; Android clears clipboard contents automatically after a while.
 */
@Composable private fun CopyWordsButton(words: List<String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(onClick = {
        val clip = android.content.ClipData.newPlainText("Recovery passphrase", words.joinToString(" "))
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
    }, modifier = Modifier.fillMaxWidth()) { Text("Copy words") }
}

@Composable private fun WordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.withIndex().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (index, word) -> Text("${index + 1}. $word", Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

/** First run: show the words, then require three of them before the notes open. */
@Composable fun PassphraseSetupScreen(vm: NotesViewModel, restore: () -> Unit) {
    val words = vm.setupWords ?: return
    SecureWindow()
    var checking by remember(words) { mutableStateOf(false) }
    val positions = remember(words) { words.indices.shuffled(SecureRandom()).take(3).sorted() }
    val answers = remember(words) { mutableStateListOf("", "", "") }
    Scaffold(topBar = { TopAppBar(title = { Text("Recovery passphrase") }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!checking) {
                Text("Write these ${words.size} words down in order, or copy them into your password manager, and keep them somewhere safe. They are the only way to open your notes on a new phone, or on this phone if its unlock key is lost. They also encrypt your backups.")
                WordGrid(words)
                CopyWordsButton(words)
                Text("Anyone with these words and a backup file can read your notes. Never share them or store them in a photo or an unencrypted note.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { checking = true }, modifier = Modifier.fillMaxWidth()) { Text("I've saved them") }
                TextButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Restore from a backup instead") }
            } else {
                Text("To confirm you saved your passphrase, enter these words from it.")
                positions.forEachIndexed { i, position ->
                    OutlinedTextField(answers[i], { answers[i] = it }, Modifier.fillMaxWidth(), label = { Text("Word ${position + 1}") }, singleLine = true, keyboardOptions = wordKeyboard)
                }
                val correct = positions.indices.all { Passphrase.normalize(answers[it]) == words[positions[it]] }
                Button(onClick = vm::confirmPassphrase, enabled = correct, modifier = Modifier.fillMaxWidth()) { Text("Confirm") }
                TextButton(onClick = { checking = false; answers.indices.forEach { answers[it] = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Show the words again") }
            }
        }
    }
}

@Composable fun RevealPassphraseDialog(words: List<String>, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, properties = secureDialog, title = { Text("Recovery passphrase") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WordGrid(words)
            CopyWordsButton(words)
            Text("These words open your notes and backups on any phone. Never share them.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

/** Entry for an existing passphrase: same-device recovery, or the passphrase that encrypted a backup. */
@Composable fun PassphraseDialog(wordlist: Set<String>, title: String, message: String, confirmLabel: String, dismiss: () -> Unit, submit: (CharArray) -> Unit) {
    var text by remember { mutableStateOf("") }
    val entered = Passphrase.words(text)
    val unknown = Passphrase.unknownWords(text, wordlist)
    AlertDialog(onDismissRequest = { text = ""; dismiss() }, properties = secureDialog, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Recovery passphrase") }, minLines = 2, keyboardOptions = wordKeyboard,
                supportingText = { Text(if (unknown.isNotEmpty()) "Not in the word list: ${unknown.joinToString(", ")}" else "${entered.size} of ${Passphrase.WORDS} words") },
                isError = unknown.isNotEmpty() || entered.size > Passphrase.WORDS)
        }
    }, confirmButton = {
        TextButton(enabled = Passphrase.isComplete(text, wordlist), onClick = { val chars = Passphrase.normalize(text).toCharArray(); text = ""; submit(chars) }) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onClick = { text = ""; dismiss() }) { Text("Cancel") } })
}
