package app.ownplay.mobile.downloads.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors

@Composable
internal fun DownloadRemovalDialog(
    canDeleteLocalFile: Boolean,
    deleteLocalFile: Boolean,
    busy: Boolean,
    onDeleteLocalFileChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Remove from OwnPlay?",
    message: String = "This removes OwnPlay's entry. The local file is kept unless you explicitly choose to delete it.",
    deleteLocalFileLabel: String = "Delete the local file too (OwnPlay Downloads)",
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                if (canDeleteLocalFile) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deleteLocalFile,
                            enabled = !busy,
                            onCheckedChange = onDeleteLocalFileChange,
                        )
                        Text(deleteLocalFileLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                Text("Remove", color = OwnPlayColors.TextPrimary)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
