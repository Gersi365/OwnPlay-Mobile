package app.ownplay.mobile.feature.library.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.downloads.domain.DownloadActionHandler
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadNotificationPermissionPromptPolicy
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadUserAction
import app.ownplay.mobile.downloads.domain.DownloadUserActionPolicy
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingItem
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LibraryDownloadActions(
    request: DownloadRequest,
    item: DownloadItem?,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    offlineResumeAvailable: Boolean = false,
) {
    val handler = remember(repository) { DownloadActionHandler(repository) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val permissionPreferences = remember(application) {
        application.services.downloadNotificationPermissionPreferences
    }
    val notificationPermissionPrompted by permissionPreferences.prompted.collectAsState(initial = false)
    var busy by remember(request.sourceId, request.mediaKind, request.contentId) { mutableStateOf(false) }
    var failed by remember(request.sourceId, request.mediaKind, request.contentId) { mutableStateOf(false) }
    var pendingPermissionAction by remember(request.sourceId, request.mediaKind, request.contentId) {
        mutableStateOf<DownloadUserAction?>(null)
    }
    val action = DownloadUserActionPolicy.primary(item?.status)

    fun perform(selected: DownloadUserAction) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                if (selected == DownloadUserAction.PLAY_OFFLINE) {
                    val target = item?.let { offlinePlaybackTarget(request.sourceId, it) }
                    if (target == null) failed = true
                    else playbackSessionController.activateLibraryMedia(target)
                } else {
                    failed = !handler.execute(selected, request, item?.downloadId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        val selected = pendingPermissionAction ?: return@rememberLauncherForActivityResult
        pendingPermissionAction = null
        perform(selected)
    }

    fun dispatch(selected: DownloadUserAction) {
        if (busy || pendingPermissionAction != null) return
        val startsDownload = selected == DownloadUserAction.DOWNLOAD || selected == DownloadUserAction.RETRY
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (
            startsDownload &&
            DownloadNotificationPermissionPromptPolicy.shouldRequest(
                sdkInt = Build.VERSION.SDK_INT,
                notificationsGranted = notificationsGranted,
                alreadyPrompted = notificationPermissionPrompted,
            )
        ) {
            pendingPermissionAction = selected
            scope.launch { permissionPreferences.markPrompted() }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perform(selected)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item?.let { Text(downloadStatusLabel(it), color = OwnPlayColors.TextSecondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action != null) {
                TextButton(enabled = !busy, onClick = { dispatch(action) }) {
                    Text(
                        when {
                            busy -> "Working…"
                            action == DownloadUserAction.RETRY && item?.status == DownloadStatus.MISSING ->
                                "Download again"
                            else -> downloadActionLabel(action, offlineResumeAvailable)
                        },
                    )
                }
            }
            if (DownloadUserActionPolicy.canRemove(item?.status)) {
                TextButton(enabled = !busy, onClick = { dispatch(DownloadUserAction.REMOVE) }) {
                    Text("Remove")
                }
            }
        }
        if (failed) {
            Text("The download action could not be completed. Try again.", color = OwnPlayColors.TextMuted)
        }
    }
}

internal fun offlinePlaybackTarget(sourceId: SourceId, item: DownloadItem): PlaybackTarget.Library? {
    if (item.sourceId != sourceId || item.status != DownloadStatus.COMPLETED ||
        item.localReference.isNullOrBlank() || item.verifiedBytes == null ||
        item.verifiedBytes != item.bytesDownloaded
    ) return null
    return when (item.mediaKind) {
        DownloadMediaKind.MOVIE -> PlaybackTarget.Movie(sourceId, item.contentId, item.downloadId.value)
        DownloadMediaKind.EPISODE -> PlaybackTarget.Episode(sourceId, item.contentId, item.downloadId.value)
    }
}

internal fun downloadActionLabel(
    action: DownloadUserAction,
    offlineResumeAvailable: Boolean = false,
): String = when (action) {
    DownloadUserAction.DOWNLOAD -> "Download"
    DownloadUserAction.PAUSE -> "Pause"
    DownloadUserAction.RESUME -> "Resume"
    DownloadUserAction.RETRY -> "Retry"
    DownloadUserAction.PLAY_OFFLINE -> if (offlineResumeAvailable) "Resume Offline" else "Play Offline"
    DownloadUserAction.REMOVE -> "Remove"
}

internal fun hasOfflineResumeProgress(
    continueWatching: List<LibraryContinueWatchingItem>,
    mediaKind: DownloadMediaKind,
    contentId: String,
): Boolean {
    val expectedKind = when (mediaKind) {
        DownloadMediaKind.MOVIE -> LibraryContentKind.MOVIE
        DownloadMediaKind.EPISODE -> LibraryContentKind.EPISODE
    }
    return continueWatching.any { item ->
        item.contentKind == expectedKind && item.contentId == contentId
    }
}

private fun downloadStatusLabel(item: DownloadItem): String = when (item.status) {
    DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> item.totalBytes?.let { total ->
        val percent = (item.bytesDownloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
        "Downloading • $percent%"
    } ?: "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Downloaded"
    DownloadStatus.FAILED -> "Download failed"
    DownloadStatus.MISSING -> "Missing · local copy unavailable or invalid"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.UNKNOWN -> "Needs attention"
}
