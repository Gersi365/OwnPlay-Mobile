package app.ownplay.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.ownplay.player.download.OfflineDownload

@Composable
internal fun DownloadRemovalConfirmationDialog(
    download: OfflineDownload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocusRequester = remember(download.downloadId) { FocusRequester() }

    LaunchedEffect(download.downloadId) {
        cancelFocusRequester.requestFocus()
    }

    VNextConfirmationDialog(
        title = "Remove download?",
        message =
            "OwnPlay will cancel any active transfer, remove “${download.title}” from " +
                "Library, and delete its offline file from this device. You can download it again later.",
        confirmLabel = "Remove",
        confirmDestructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        dismissModifier = Modifier.focusRequester(cancelFocusRequester),
    )
}
