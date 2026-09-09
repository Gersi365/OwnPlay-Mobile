package app.ownplay.player.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.ui.theme.OwnPlayMediaLayout
import app.ownplay.player.ui.theme.OwnPlaySectionHeader
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.download.queuedDownloadStatusLabel
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import kotlinx.coroutines.launch

@Composable
internal fun DownloadsSettingsScreen(
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val runtime = remember(context) {
        OfflineDownloadFeatureRuntime(context.applicationContext)
    }
    DisposableEffect(runtime) {
        onDispose { runtime.close() }
    }
    val downloads by runtime.observeAll().collectAsState(initial = emptyList())
    val presentation = remember(downloads) { DownloadsPresentationPolicy.items(downloads) }
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<OfflineDownload?>(null) }
    var resumeDownloadIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(downloads) {
        val resolvedResumeIds = mutableSetOf<String>()
        for (download in downloads) {
            if (download.state != DownloadStates.COMPLETED) continue
            val progress = runtime.playbackProgress(download.downloadId)
            if (progress != null && !progress.completed && progress.positionMs > 0L) {
                resolvedResumeIds += download.downloadId
            }
        }
        resumeDownloadIds = resolvedResumeIds
    }

    pendingRemoval?.let { download ->
        DownloadRemovalConfirmationDialog(
            download = download,
            onConfirm = {
                pendingRemoval = null
                scope.launch { runtime.remove(download.downloadId) }
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .align(Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("‹ Settings") }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Movies and series episodes saved for offline use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No downloads yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Use Download from a movie or episode details screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(presentation, key = { it.key }, contentType = {
                when (it) {
                    is DownloadsListItem.Section -> "section"
                    is DownloadsListItem.Group -> "group"
                    is DownloadsListItem.Media -> "media"
                }
            }) { item ->
                when (item) {
                    is DownloadsListItem.Section -> OwnPlaySectionHeader(
                        title = item.section.title,
                        action = {
                            Text(
                                text = item.count.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    is DownloadsListItem.Group -> Text(
                        text = item.title,
                        modifier = Modifier.padding(
                            start = if (item.isSeason) 12.dp else 0.dp,
                            top = if (item.isSeason) 0.dp else 8.dp,
                        ),
                        style = if (item.isSeason) MaterialTheme.typography.labelLarge
                            else MaterialTheme.typography.titleMedium,
                        color = if (item.isSeason) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    )
                    is DownloadsListItem.Media -> {
                        val download = item.download
                        DownloadRow(
                            download = download,
                            resumeAvailable = download.downloadId in resumeDownloadIds,
                            onPlayOffline = { startFromBeginning ->
                                DownloadPlaybackBridge.request(
                                    download = download,
                                    startFromBeginning = startFromBeginning,
                                )
                            },
                            onPause = {
                                scope.launch { runtime.pause(download.downloadId) }
                            },
                            onResume = {
                                scope.launch { runtime.resume(download.downloadId) }
                            },
                            onRetry = {
                                scope.launch { runtime.retry(download.downloadId) }
                            },
                            onRemove = {
                                pendingRemoval = download
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    download: OfflineDownload,
    resumeAvailable: Boolean,
    onPlayOffline: (startFromBeginning: Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RemotePoster(
                    url = download.posterUrl,
                    title = download.title,
                    modifier = Modifier.width(56.dp).aspectRatio(OwnPlayMediaLayout.PosterAspectRatio),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = downloadSecondaryLabel(download),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (download.state) {
                    DownloadStates.QUEUED,
                    DownloadStates.DOWNLOADING,
                    -> IconButton(onClick = onPause) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause download")
                    }
                    DownloadStates.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume download")
                    }
                    DownloadStates.COMPLETED -> TextButton(onClick = { onPlayOffline(false) }) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(if (resumeAvailable) "Resume Offline" else "Play Offline")
                    }
                    DownloadStates.FAILED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry download")
                    }
                    else -> Unit
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                }
            }

            when (download.state) {
                DownloadStates.DOWNLOADING -> {
                    val progress = download.progressFraction
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "Downloading · ${humanBytes(download.bytesDownloaded)}" +
                            download.totalBytes?.let { " / ${humanBytes(it)}" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadStates.QUEUED -> Text(
                    text = queuedDownloadStatusLabel(download.failureReason),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadStates.PAUSED -> Text(
                    text = "Paused · ${humanBytes(download.bytesDownloaded)}" +
                        download.totalBytes?.let { " / ${humanBytes(it)}" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadStates.COMPLETED -> {
                    Text(
                        text = "Available offline · Local file · ${downloadStorageLabel(download)} · ${humanBytes(download.bytesDownloaded)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (resumeAvailable) {
                        TextButton(onClick = { onPlayOffline(true) }) {
                            Text("Play from beginning")
                        }
                    }
                }
                DownloadStates.FAILED -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = download.failureReason ?: "Download failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun downloadSecondaryLabel(download: OfflineDownload): String {
    if (download.mediaKind != DownloadMediaKinds.SERIES_EPISODE) return "Movie"
    val episode = listOfNotNull(
        download.seasonNumber?.let { "S${it.toString().padStart(2, '0')}" },
        download.episodeNumber?.let { "E${it.toString().padStart(2, '0')}" },
    ).joinToString(" · ")
    return listOfNotNull(
        download.seriesTitle?.takeIf(String::isNotBlank),
        episode.takeIf(String::isNotBlank),
    ).joinToString(" · ").ifBlank { "Series episode" }
}

private fun downloadStorageLabel(download: OfflineDownload): String =
    if (download.savedToDownloads) "OwnPlay Downloads" else "OwnPlay private storage"

private fun humanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
        safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
        safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
        else -> "$safe B"
    }
}
