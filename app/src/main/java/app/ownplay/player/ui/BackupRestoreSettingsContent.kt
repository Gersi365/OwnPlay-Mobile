package app.ownplay.player.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ownplay.player.backup.BackupExportResult
import app.ownplay.player.backup.BackupRestoreFailureReason
import app.ownplay.player.backup.BackupRestoreResult
import app.ownplay.player.backup.PersonalizationBackupService
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_BACKUP_CHARS = 5_000_000

@Composable
internal fun BackupRestoreSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) {
        PersonalizationBackupService(context.applicationContext)
    }
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Creating backup…"
            status = when (val result = service.exportBackup()) {
                is BackupExportResult.Success -> {
                    val written = writeBackup(context, uri, result.content)
                    if (!written) {
                        "Backup could not be written."
                    } else {
                        buildString {
                            append("Backup exported: ")
                            append(result.channelRecords)
                            append(" channel records, ")
                            append(result.groups)
                            append(" groups, ")
                            append(result.memberships)
                            append(" memberships.")
                            if (result.omittedLogoOverrides > 0) {
                                append(" ")
                                append(result.omittedLogoOverrides)
                                append(" secure logo override(s) were intentionally omitted.")
                            }
                        }
                    }
                }
                BackupExportResult.Failure -> "Backup export failed safely. No file data was written."
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Validating backup…"
            val raw = readBackup(context, uri)
            status = if (raw == null) {
                "Backup could not be read or exceeds the supported size limit."
            } else {
                restoreStatus(service.restoreBackup(raw))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsActionRow(
            title = "Backup personalization",
            detail = "Personalization only · credentials excluded",
            actionLabel = "Export",
            onClick = { exportLauncher.launch("ownplay-personalization-v1.json") },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsActionRow(
            title = "Restore personalization",
            detail = "Import a previous OwnPlay personalization backup",
            actionLabel = "Import",
            onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain"))
            },
        )
        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private suspend fun writeBackup(
    context: Context,
    uri: Uri,
    content: String,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
        stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private suspend fun readBackup(
    context: Context,
    uri: Uri,
): String? = withContext(Dispatchers.IO) {
    try {
        val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_CHARS) throw IOException("Backup exceeds size limit")
                output.append(buffer, 0, read)
            }
            output.toString()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun restoreStatus(result: BackupRestoreResult): String = when (result) {
    is BackupRestoreResult.Success -> buildString {
        append("Restore complete: ")
        append(result.appliedChannelRecords)
        append(" channel records, ")
        append(result.appliedGroups)
        append(" groups, ")
        append(result.appliedMemberships)
        append(" memberships applied.")
        if (result.unmatchedChannelIdentities > 0 || result.ambiguousChannelIdentities > 0) {
            append(" Skipped identities: ")
            append(result.unmatchedChannelIdentities)
            append(" unmatched, ")
            append(result.ambiguousChannelIdentities)
            append(" ambiguous.")
        }
        if (result.omittedLogoOverrides > 0) {
            append(" ")
            append(result.omittedLogoOverrides)
            append(" secure logo override(s) were not restored.")
        }
    }
    is BackupRestoreResult.Failure -> when (result.reason) {
        BackupRestoreFailureReason.INVALID_JSON -> "Restore rejected: file is not valid JSON."
        BackupRestoreFailureReason.UNSUPPORTED_FORMAT -> "Restore rejected: unsupported backup format."
        BackupRestoreFailureReason.UNSUPPORTED_VERSION -> "Restore rejected: unsupported backup version."
        BackupRestoreFailureReason.INVALID_PAYLOAD -> "Restore rejected: backup payload is invalid."
        BackupRestoreFailureReason.PERSISTENCE_FAILURE ->
            "Restore failed safely while applying data; the database transaction was rolled back."
    }
}
