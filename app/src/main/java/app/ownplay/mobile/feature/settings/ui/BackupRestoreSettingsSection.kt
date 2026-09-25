package app.ownplay.mobile.feature.settings.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.feature.settings.backup.domain.BackupExportResult
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestorePlan
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestorePreview
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestoreRepository
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestoreResult
import app.ownplay.mobile.feature.settings.backup.domain.BackupSourceRestoreAction
import app.ownplay.mobile.feature.settings.backup.domain.BackupValidationCode
import app.ownplay.mobile.feature.settings.backup.domain.BackupValidationIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_BACKUP_BYTES = 50_000_000

private enum class BackupOperation(val status: String) {
    EXPORTING("Exporting backup…"),
    CHECKING_BACKUP("Checking backup…"),
    RESTORING("Restoring backup…"),
}

@Composable
internal fun BackupRestoreSection(
    repository: BackupRestoreRepository,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPlan by remember { mutableStateOf<BackupRestorePlan?>(null) }
    var operation by remember { mutableStateOf<BackupOperation?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            operation = BackupOperation.EXPORTING
            val message = when (val result = repository.exportBackup()) {
                is BackupExportResult.Success ->
                    if (writeBackupFile(context, uri, result.bytes)) "Backup exported." else "Backup export failed."
                BackupExportResult.Failure -> "Backup export failed."
            }
            operation = null
            onMessage(message)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            operation = BackupOperation.CHECKING_BACKUP
            val backup = readBackupFile(context, uri)
            if (backup == null) {
                operation = null
                onMessage("Backup file could not be read.")
                return@launch
            }
            when (val preview = repository.previewRestore(backup)) {
                is BackupRestorePreview.Ready -> {
                    pendingBackup = backup
                    pendingPlan = preview.plan
                }
                is BackupRestorePreview.Conflicted -> onMessage(
                    "Restore blocked by ${preview.plan.sourceResolutions.count { it.action == BackupSourceRestoreAction.CONFLICT }} source conflict(s).",
                )
                is BackupRestorePreview.Rejected -> onMessage(
                    backupRejectedMessage(preview.issues),
                )
                BackupRestorePreview.StorageFailure -> onMessage("Restore preview could not read local state.")
            }
            operation = null
        }
    }

    SettingsSectionCard(
        title = "Backup & restore",
        subtitle = "Credentials and provider cache are excluded",
    ) {
        Text(
            "Create one portable OwnPlay backup with sources, settings and personalization.",
            color = OwnPlayColors.TextSecondary,
        )
        Text(
            "Sources restored without matching local credentials stay disabled until credentials are re-entered.",
            color = OwnPlayColors.TextMuted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                enabled = operation == null,
                onClick = { exportLauncher.launch("ownplay-backup.json") },
            ) {
                Text("Export backup")
            }
            TextButton(
                enabled = operation == null,
                onClick = {
                    importLauncher.launch(arrayOf("*/*"))
                },
            ) {
                Text("Restore from file")
            }
        }
        operation?.let { activeOperation ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(activeOperation.status, color = OwnPlayColors.TextMuted)
            }
        }
    }

    val plan = pendingPlan
    if (plan != null && pendingBackup != null) {
        RestoreConfirmationDialog(
            plan = plan,
            restoring = operation == BackupOperation.RESTORING,
            onDismiss = {
                pendingBackup = null
                pendingPlan = null
            },
            onConfirm = {
                val backup = pendingBackup ?: return@RestoreConfirmationDialog
                operation = BackupOperation.RESTORING
                scope.launch {
                    val message = restoreMessage(repository.restore(backup))
                    operation = null
                    pendingBackup = null
                    pendingPlan = null
                    onMessage(message)
                }
            },
        )
    }
}

@Composable
private fun RestoreConfirmationDialog(
    plan: BackupRestorePlan,
    restoring: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val merged = plan.sourceResolutions.count { it.action == BackupSourceRestoreAction.MERGE_EXISTING }
    val created = plan.sourceResolutions.count { it.action == BackupSourceRestoreAction.CREATE_DISABLED }
    AlertDialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        title = { Text("Restore backup?") },
        text = {
            val summary = plan.summary
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$merged source(s) will merge; $created new source(s) require credentials.")
                Text("Sources: ${summary.sourceCount}")
                Text("Favorites: ${summary.favoriteCount}")
                Text("Live organization/manual placements: ${summary.liveOrganizationItemCount}")
                Text("Library preferences: ${summary.libraryPreferenceItemCount}")
                Text(
                    buildString {
                        append("Settings: Display, Playback")
                        if (summary.includesRefreshSettings) append(", Refresh")
                        append(", Downloads")
                    },
                )
                Text(
                    "Provider cache, playback progress and downloaded files are not restored.",
                    color = OwnPlayColors.TextMuted,
                )
                if (restoring) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Restoring backup…", color = OwnPlayColors.TextMuted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !restoring, onClick = onConfirm) { Text("Restore") }
        },
        dismissButton = {
            TextButton(enabled = !restoring, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun restoreMessage(result: BackupRestoreResult): String = when (result) {
    is BackupRestoreResult.Success -> {
        val report = result.report
        val skipped = report.skippedCategoryPersonalization +
            report.skippedChannelPersonalization + report.skippedMediaFavorites +
            report.skippedLivePlacements + report.skippedCustomGroups +
            report.skippedCustomGroupMemberships
        buildString {
            append("Restore complete: ${report.mergedSources} merged, ")
            append("${report.createdDisabledSources} created disabled")
            if (skipped > 0) append(", $skipped personalization item(s) skipped")
            if (report.refreshScheduleSyncFailures > 0) {
                append(", ${report.refreshScheduleSyncFailures} refresh schedule(s) need retry")
            }
            append('.')
        }
    }
    is BackupRestoreResult.Conflicted -> "Restore blocked by source conflicts."
    is BackupRestoreResult.Rejected -> backupRejectedMessage(result.issues)
    BackupRestoreResult.StorageFailure -> "Restore failed without completing durable state changes."
}

private fun backupRejectedMessage(issues: List<BackupValidationIssue>): String =
    if (issues.any { it.code == BackupValidationCode.UNSUPPORTED_NEWER_VERSION }) {
        "Backup requires a newer version of OwnPlay."
    } else {
        "Backup rejected: ${issues.firstOrNull()?.code ?: "invalid data"}."
    }

private suspend fun writeBackupFile(context: Context, uri: Uri, bytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Output stream unavailable")
        }.isSuccess
    }

private suspend fun readBackupFile(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BACKUP_BYTES) {
                        error("Backup file is too large")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Input stream unavailable")
        }.getOrNull()
    }
