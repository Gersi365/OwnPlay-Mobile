package app.ownplay.mobile.downloads.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayFeaturePlaceholder
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.downloads.domain.DownloadActionHandler
import app.ownplay.mobile.downloads.domain.DownloadDetailsNavigation
import app.ownplay.mobile.downloads.domain.DownloadEpisodeContext
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadUserAction
import app.ownplay.mobile.downloads.domain.DownloadUserActionPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    onPlaybackStarted: () -> Unit = {},
    onOpenDetails: (DownloadDetailsNavigation) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val source by activeSourceFlow.collectAsState(initial = null)

    val activeSource = source
    if (activeSource == null) {
        OwnPlayFeaturePlaceholder(
            title = "Downloads",
            message = "Add or select a source in Settings to manage offline media.",
            modifier = modifier,
        )
        return
    }

    val downloadsFlow = remember(services.downloadRepository, activeSource.sourceId) {
        services.downloadRepository.observeDownloads(activeSource.sourceId)
    }
    val downloads by downloadsFlow.collectAsState(initial = emptyList())
    var episodeContexts by remember(activeSource.sourceId) {
        mutableStateOf<Map<String, DownloadEpisodeContext>>(emptyMap())
    }

    LaunchedEffect(downloads) {
        val resolved = linkedMapOf<String, DownloadEpisodeContext>()
        downloads
            .filter { it.mediaKind == DownloadMediaKind.EPISODE }
            .forEach { item ->
                services.downloadPresentationMetadataResolver
                    .resolveEpisodeContext(item)
                    ?.let { context -> resolved[item.downloadId.value] = context }
            }
        episodeContexts = resolved
    }

    DownloadCatalog(
        downloads = downloads,
        episodeContexts = episodeContexts,
        repository = services.downloadRepository,
        playbackSessionController = services.playbackSessionController,
        onPlaybackStarted = onPlaybackStarted,
        onOpenDetails = onOpenDetails,
        modifier = modifier,
    )
}

@Composable
private fun DownloadCatalog(
    downloads: List<DownloadItem>,
    episodeContexts: Map<String, DownloadEpisodeContext>,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    onPlaybackStarted: () -> Unit,
    onOpenDetails: (DownloadDetailsNavigation) -> Unit,
    modifier: Modifier,
) {
    val movies = downloads.filter { it.mediaKind == DownloadMediaKind.MOVIE }
    val episodes = downloads.filter { it.mediaKind == DownloadMediaKind.EPISODE }
    val waitingOrder = downloads
        .filter { it.status == DownloadStatus.WAITING_FOR_WIFI || it.status == DownloadStatus.QUEUED }
        .sortedWith(compareBy<DownloadItem> { it.createdAtEpochMs }.thenBy { it.downloadId.value })
        .mapIndexed { index, item -> item.downloadId.value to index + 1 }
        .toMap()
    val seriesGroups = episodes
        .groupBy { episodeContexts[it.downloadId.value]?.seriesTitle ?: "Other episodes" }
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<DownloadItem>>> { it.key.lowercase(Locale.ROOT) }
                .thenBy { it.key },
        )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Downloads",
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = "Up to 3 downloads run at once. Additional items remain in FIFO order.",
                color = OwnPlayColors.TextSecondary,
            )
        }

        if (downloads.isEmpty()) {
            item {
                Text(
                    text = "No downloads yet. Start a Movie or Episode download from Library.",
                    color = OwnPlayColors.TextMuted,
                )
            }
        }

        if (movies.isNotEmpty()) {
            item { DownloadSectionTitle("Movies") }
            items(movies, key = { it.downloadId.value }) { item ->
                DownloadRow(
                    item = item,
                    episodeContext = null,
                    queuePosition = waitingOrder[item.downloadId.value],
                    repository = repository,
                    playbackSessionController = playbackSessionController,
                    onPlaybackStarted = onPlaybackStarted,
                    onOpenDetails = onOpenDetails,
                )
            }
        }

        if (seriesGroups.isNotEmpty()) {
            item { DownloadSectionTitle("Series") }
            seriesGroups.forEach { (seriesTitle, seriesItems) ->
                item(key = "series:$seriesTitle") {
                    Text(
                        text = seriesTitle,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val seasonGroups = seriesItems
                    .groupBy { episodeContexts[it.downloadId.value]?.seasonNumber }
                    .entries
                    .sortedBy { it.key ?: Int.MAX_VALUE }
                seasonGroups.forEach { (seasonNumber, seasonItems) ->
                    item(key = "season:$seriesTitle:${seasonNumber ?: "unknown"}") {
                        Text(
                            text = seasonNumber?.let { "Season ${it.toString().padStart(2, '0')}" }
                                ?: "Season unknown",
                            color = OwnPlayColors.TextSecondary,
                        )
                    }
                    items(
                        items = seasonItems.sortedWith(
                            compareBy<DownloadItem> {
                                episodeContexts[it.downloadId.value]?.episodeNumber ?: Int.MAX_VALUE
                            }.thenBy { it.downloadId.value },
                        ),
                        key = { it.downloadId.value },
                    ) { item ->
                        DownloadRow(
                            item = item,
                            episodeContext = episodeContexts[item.downloadId.value],
                            queuePosition = waitingOrder[item.downloadId.value],
                            repository = repository,
                            playbackSessionController = playbackSessionController,
                            onPlaybackStarted = onPlaybackStarted,
                    onOpenDetails = onOpenDetails,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    episodeContext: DownloadEpisodeContext?,
    queuePosition: Int?,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    onPlaybackStarted: () -> Unit,
    onOpenDetails: (DownloadDetailsNavigation) -> Unit,
) {
    val handler = remember(repository) { DownloadActionHandler(repository) }
    val scope = rememberCoroutineScope()
    var busy by remember(item.downloadId) { mutableStateOf(false) }
    var actionFailed by remember(item.downloadId) { mutableStateOf(false) }
    val primary = DownloadUserActionPolicy.primary(item.status)

    fun play(offline: Boolean) {
        val target = when (item.mediaKind) {
            DownloadMediaKind.MOVIE -> PlaybackTarget.Movie(
                sourceId = item.sourceId,
                movieId = item.contentId,
                offlineDownloadId = item.downloadId.value.takeIf { offline },
            )
            DownloadMediaKind.EPISODE -> PlaybackTarget.Episode(
                sourceId = item.sourceId,
                episodeId = item.contentId,
                offlineDownloadId = item.downloadId.value.takeIf { offline },
                seriesId = episodeContext?.seriesId,
            )
        }
        scope.launch {
            playbackSessionController.activateLibraryMedia(target)
            onPlaybackStarted()
        }
    }

    fun dispatch(action: DownloadUserAction) {
        if (busy) return
        if (action == DownloadUserAction.PLAY_OFFLINE) {
            play(offline = true)
            return
        }
        busy = true
        actionFailed = false
        scope.launch {
            try {
                val request = DownloadRequest(
                    sourceId = item.sourceId,
                    mediaKind = item.mediaKind,
                    contentId = item.contentId,
                    title = item.title,
                )
                actionFailed = !handler.execute(action, request, item.downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionFailed = true
            } finally {
                busy = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.Surface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = episodeContext?.let { context ->
                    "E${context.episodeNumber.toString().padStart(2, '0')} · ${item.title}"
                } ?: item.title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = downloadStatusText(item, queuePosition),
                color = if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.MISSING) {
                    OwnPlayColors.Error
                } else {
                    OwnPlayColors.TextSecondary
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onOpenDetails(
                            DownloadDetailsNavigation(
                                downloadId = item.downloadId,
                                sourceId = item.sourceId,
                                mediaKind = item.mediaKind,
                                contentId = item.contentId,
                            ),
                        )
                    },
                ) {
                    Text("Details")
                }
                primary?.let { action ->
                    TextButton(enabled = !busy, onClick = { dispatch(action) }) {
                        Text(
                            when {
                                busy -> "Working…"
                                action == DownloadUserAction.RETRY && item.status == DownloadStatus.MISSING ->
                                    "Download again"
                                action == DownloadUserAction.PLAY_OFFLINE -> "Play offline"
                                action == DownloadUserAction.DOWNLOAD -> "Download"
                                action == DownloadUserAction.PAUSE -> "Pause"
                                action == DownloadUserAction.RESUME -> "Resume"
                                action == DownloadUserAction.RETRY -> "Retry"
                                else -> "Open"
                            },
                        )
                    }
                }
                if (item.status == DownloadStatus.MISSING) {
                    TextButton(enabled = !busy, onClick = { play(offline = false) }) {
                        Text("Play online")
                    }
                }
                if (DownloadUserActionPolicy.canRemove(item.status)) {
                    TextButton(
                        enabled = !busy,
                        onClick = { dispatch(DownloadUserAction.REMOVE) },
                    ) {
                        Text("Remove")
                    }
                }
            }
            if (actionFailed) {
                Text(
                    text = "The download action could not be completed.",
                    color = OwnPlayColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun DownloadSectionTitle(title: String) {
    Text(
        text = title,
        color = OwnPlayColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun downloadStatusText(
    item: DownloadItem,
    queuePosition: Int?,
): String = when (item.status) {
    DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi" + queuePosition?.let { " · Queue $it" }.orEmpty()
    DownloadStatus.QUEUED -> "Queued" + queuePosition?.let { " · Queue $it" }.orEmpty()
    DownloadStatus.DOWNLOADING -> item.totalBytes?.let { total ->
        val percent = (item.bytesDownloaded.toDouble() / total * 100.0).toInt().coerceIn(0, 100)
        "Downloading · $percent%"
    } ?: "Downloading · ${formatBytes(item.bytesDownloaded)}"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Complete · ${formatBytes(item.verifiedBytes ?: item.bytesDownloaded)}"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.MISSING -> "Missing · local copy unavailable or invalid"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.UNKNOWN -> "Needs attention"
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.ROOT, value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KB".format(Locale.ROOT, value / 1024.0)
        else -> "$value B"
    }
}
