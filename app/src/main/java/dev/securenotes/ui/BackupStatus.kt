package dev.securenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.securenotes.R
import dev.securenotes.backup.*

internal fun backupErrorResource(error: BackupError, setup: Boolean = false): Int = when {
    setup && error.problem in setOf(BackupProblem.IO, BackupProblem.INVALID_CONFIGURATION, BackupProblem.ACTIVE_DAMAGED, BackupProblem.ATTACHMENT_DAMAGED) -> R.string.backup_setup_io
    setup && error.problem == BackupProblem.ACCESS -> R.string.backup_setup_access
    error.step == "verify-active" && error.problem == BackupProblem.ACCESS -> R.string.backup_active_access
    error.step == "verify-active" && error.problem == BackupProblem.IO -> R.string.backup_active_unverified
    error.step == "check-attachments" && error.problem == BackupProblem.ACCESS -> R.string.backup_attachments_access
    error.step == "check-attachments" && error.problem == BackupProblem.IO -> R.string.backup_attachments_unverified
    error.step == "attachment-identity" && error.problem == BackupProblem.ATTACHMENT_DAMAGED -> R.string.backup_attachment_conflict
    else -> when (error.problem) {
        BackupProblem.IO -> R.string.backup_io
        BackupProblem.ACCESS -> R.string.backup_access
        BackupProblem.MANIFEST_TOO_LARGE -> R.string.backup_manifest
        BackupProblem.PHRASE_MISSING -> R.string.backup_phrase_missing
        BackupProblem.PHRASE_CHANGED -> R.string.backup_phrase_changed
        BackupProblem.ACTIVE_DAMAGED -> R.string.backup_damaged
        BackupProblem.ATTACHMENT_DAMAGED -> R.string.backup_attachment_damaged
        BackupProblem.INVALID_CONFIGURATION -> R.string.backup_configuration
        BackupProblem.BUSY -> R.string.backup_busy
        BackupProblem.RESTART_REQUIRED -> R.string.backup_restart
        BackupProblem.SELECT_AGAIN -> R.string.backup_select_again
    }
}
@Composable internal fun backupErrorText(error: BackupError, setup: Boolean = false): String =
    stringResource(backupErrorResource(error, setup)) + if (error.diagnostic.isNotBlank()) " (${error.step}: ${error.diagnostic})" else ""

@Composable internal fun backupBannerMessage(status: BackupStatus?): String? = when {
    status == null -> null
    status.restartRequired -> stringResource(R.string.backup_restart)
    status.configuration.mode == BackupMode.LEGACY -> stringResource(R.string.backup_migration)
    status.error != null -> backupErrorText(status.error)
    else -> null
}

@Composable internal fun BackupBanner(message: String, enabled: Boolean, review: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = review, enabled = enabled) { Text(stringResource(R.string.backup_review)) }
        }
    }
}
