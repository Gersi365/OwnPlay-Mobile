package app.ownplay.mobile.downloads.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import app.ownplay.mobile.downloads.domain.DownloadOrigin
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadUserAction
import app.ownplay.mobile.downloads.domain.DownloadUserActionPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    onPlaybackStarted: () -> Unit = {},
    onOpenDetails: (DownloadDetailsNavigation) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val recordings by services.liveRecordingRepository.recordings.collectAsState(initial = emptyList())
    var showingRecordings by remember { mutableStateOf(false) }
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val source by activeSourceFlow.collectAsState(initial = null)

    val activeSource = source
    if (showingRecordings) {
        Column(modifier = modifier.fillMaxSize()) {
            DownloadSectionTabs(
                showingRecordings = true,
                onSelectDownloads = { showingRecordings = false },
                onSelectRecordings = { showingRecordings = true },
            )
            LiveRecordingsCatalog(
                recordings = recordings,
                onCancel = services.liveRecordingScheduler::cancel,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    if (activeSource == null) {
        Column(modifier = modifier.fillMaxSize()) {
            DownloadSectionTabs(
                showingRecordings = false,
                onSelectDownloads = { showingRecordings = false },
                onSelectRecordings = { showingRecordings = true },
            )
            OwnPlayFeaturePlaceholder(
                title = "Downloads",
                message = "Add or select a source in Settings to manage offline media.",
                modifier = Modifier.weight(1f),
                actionLabel = "Open Settings",
                onAction = onOpenSettings,
            )
        }
        return
    }

    val downloadsFlow = remember(services.downloadRepository, activeSource.sourceId) {
        services.downloadRepository.observeDownloads(activeSource.sourceId)
    }
    val downloads by downloadsFlow.collectAsState(initial = emptyList())
    val importFolderUri by services.externalDownloadImportManager.folderUri.collectAsState(initial = null)
    val importScope = rememberCoroutineScope()
    var importBusy by remember(activeSource.sourceId) { mutableStateOf(false) }
    var importMessage by remember(activeSource.sourceId) { mutableStateOf<String?>(null) }

    fun scanImportFolder() {
        if (importBusy) return
        importBusy = true
        importScope.launch {
            try {
                val result = services.externalDownloadImportManager.scan(activeSource.sourceId)
                importMessage = when {
                    result.accessRequired -> "Choose the configured OwnPlay Downloads folder to enable import scanning."
                    result.imported > 0 -> "Imported ${result.imported} local file${if (result.imported == 1) "" else "s"}." +
                        if (result.unmatched > 0) " ${result.unmatched} file${if (result.unmatched == 1) "" else "s"} could not be matched safely." else ""
                    result.unmatched > 0 -> "No new matches. ${result.unmatched} file${if (result.unmatched == 1) "" else "s"} could not be matched safely."
                    result.conflicts > 0 -> "${result.conflicts} file${if (result.conflicts == 1) "" else "s"} conflict with existing download state and were left unchanged."
                    result.discoveredVideoFiles > 0 -> "Folder scan complete. No new files to import."
                    else -> "Folder scan complete. No video files found."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "The folder scan could not be completed."
            } finally {
                importBusy = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        importMessage = null
        importScope.launch {
            try {
                val granted = services.externalDownloadImportManager.grantFolder(uri)
                importMessage = if (granted) {
                    "Folder linked. Scanning…"
                } else {
                    "Choose the exact download folder configured in OwnPlay Settings."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "OwnPlay could not keep access to that folder."
            } finally {
                importBusy = false
            }
        }
    }

    LaunchedEffect(activeSource.sourceId, importFolderUri) {
        if (importFolderUri != null) {
            importBusy = true
            try {
                val result = services.externalDownloadImportManager.scan(activeSource.sourceId)
                importMessage = when {
                    result.accessRequired -> "Folder access needs to be selected again."
                    result.imported > 0 -> "Imported ${result.imported} local file${if (result.imported == 1) "" else "s"}." +
                        if (result.unmatched > 0) " ${result.unmatched} file${if (result.unmatched == 1) "" else "s"} could not be matched safely." else ""
                    result.unmatched > 0 -> "${result.unmatched} local file${if (result.unmatched == 1) "" else "s"} could not be matched safely."
                    result.conflicts > 0 -> "${result.conflicts} local file${if (result.conflicts == 1) "" else "s"} conflict with existing download state and were left unchanged."
                    else -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "The folder scan could not be completed."
            } finally {
                importBusy = false
            }
        }
    }

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

    Column(modifier = modifier.fillMaxSize()) {
        DownloadSectionTabs(
            showingRecordings = false,
            onSelectDownloads = { showingRecordings = false },
            onSelectRecordings = { showingRecordings = true },
        )
        DownloadCatalog(
            downloads = downloads,
            episodeContexts = episodeContexts,
            repository = services.downloadRepository,
            playbackSessionController = services.playbackSessionController,
            onPlaybackStarted = onPlaybackStarted,
            onOpenDetails = onOpenDetails,
            onOpenLibrary = onOpenLibrary,
            importFolderLinked = importFolderUri != null,
            importBusy = importBusy,
            importMessage = importMessage,
            onChooseImportFolder = { folderPicker.launch(null) },
            onScanImportFolder = ::scanImportFolder,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DownloadSectionTabs(
    showingRecordings: Boolean,
    onSelectDownloads: () -> Unit,
    onSelectRecordings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = onSelectDownloads,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(if (showingRecordings) "Downloads" else "Downloads · selected")
        }
        TextButton(
            onClick = onSelectRecordings,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(if (showingRecordings) "Recordings · selected" else "Recordings")
        }
    }
}

@Composable
private fun LiveRecordingsCatalog(
    recordings: List<LiveRecording>,
    onCancel: (String) -> Boolean,
    modifier: Modifier,
) {
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Recordings",
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (recordings.isEmpty()) {
            item {
                Text(
                    text = "No recordings yet. Record or schedule a program from the Live EPG.",
                    color = OwnPlayColors.TextSecondary,
                )
            }
        }
        items(recordings, key = LiveRecording::recordingId) { recording ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = OwnPlayShapes.Medium,
                color = OwnPlayColors.SurfaceRaised,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = recording.title,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${recording.channelName} · ${dateFormatter.format(Date(recording.startEpochSeconds * 1_000L))}",
                        color = OwnPlayColors.TextSecondary,
                    )
                    Text(
                        text = when (recording.status) {
                            LiveRecordingStatus.SCHEDULED -> "Scheduled"
                            LiveRecordingStatus.RECORDING -> "Recording"
                            LiveRecordingStatus.COMPLETED -> "Saved in OwnPlay Downloads/Recordings"
                            LiveRecordingStatus.FAILED -> recording.safeError ?: "Recording failed"
                            LiveRecordingStatus.CANCELLED -> "Cancelled"
                        },
                        color = if (recording.status == LiveRecordingStatus.FAILED) {
                            OwnPlayColors.Error
                        } else {
                            OwnPlayColors.TextSecondary
                        },
                    )
                    if (recording.status == LiveRecordingStatus.SCHEDULED) {
                        TextButton(
                            onClick = { onCancel(recording.recordingId) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Cancel recording")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCatalog(
    downloads: List<DownloadItem>,
    episodeContexts: Map<String, DownloadEpisodeContext>,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    onPlaybackStarted: () -> Unit,
    onOpenDetails: (DownloadDetailsNavigation) -> Unit,
    onOpenLibrary: () -> Unit,
    importFolderLinked: Boolean,
    importBusy: Boolean,
    importMessage: String?,
    onChooseImportFolder: () -> Unit,
    onScanImportFolder: () -> Unit,
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
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (downloads.isEmpty()) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "No downloads yet. Start a Movie or Episode download from Library.",
                        color = OwnPlayColors.TextMuted,
                    )
                    FilledTonalButton(
                        onClick = onOpenLibrary,
                        shape = OwnPlayShapes.Medium,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Browse Library")
                    }
                }
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

        item {
            LocalImportSection(
                importFolderLinked = importFolderLinked,
                importBusy = importBusy,
                importMessage = importMessage,
                onChooseImportFolder = onChooseImportFolder,
                onScanImportFolder = onScanImportFolder,
            )
        }
    }
}

@Composable
private fun LocalImportSection(
    importFolderLinked: Boolean,
    importBusy: Boolean,
    importMessage: String?,
    onChooseImportFolder: () -> Unit,
    onScanImportFolder: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Import existing files",
            color = OwnPlayColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Scan the configured Downloads folder for videos already on this device.",
            color = OwnPlayColors.TextSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                enabled = !importBusy,
                onClick = if (importFolderLinked) onScanImportFolder else onChooseImportFolder,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        importBusy -> "Scanning…"
                        importFolderLinked -> "Scan folder"
                        else -> "Choose folder"
                    },
                )
            }
            if (importFolderLinked) {
                TextButton(
                    enabled = !importBusy,
                    onClick = onChooseImportFolder,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Change folder")
                }
            }
        }
        importMessage?.let { message ->
            Text(
                text = message,
                color = OwnPlayColors.TextSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
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
    var removeConfirmationOpen by remember(item.downloadId) { mutableStateOf(false) }
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

    if (removeConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { if (!busy) removeConfirmationOpen = false },
            title = { Text("Delete offline file?") },
            text = {
                Text(
                    "This will remove the item from OwnPlay and permanently delete any local file associated with it. Do you want to continue?",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        removeConfirmationOpen = false
                        dispatch(DownloadUserAction.REMOVE)
                    },
                ) {
                    Text("Remove and delete file", color = OwnPlayColors.Error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { removeConfirmationOpen = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.SurfaceRaised,
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
            if (item.status == DownloadStatus.DOWNLOADING) {
                item.totalBytes
                    ?.takeIf { it > 0L }
                    ?.let { totalBytes ->
                        val progress = (item.bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
            }
            primary?.let { action ->
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { dispatch(action) },
                    shape = OwnPlayShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Details")
                }
                if (item.status == DownloadStatus.MISSING) {
                    TextButton(
                        enabled = !busy,
                        onClick = { play(offline = false) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Play online")
                    }
                }
                if (DownloadUserActionPolicy.canRemove(item.status)) {
                    TextButton(
                        enabled = !busy,
                        onClick = { removeConfirmationOpen = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Remove", color = OwnPlayColors.Error)
                    }
                }
            }
            if (actionFailed) {
                Text(
                    text = "The download action could not be completed.",
                    color = OwnPlayColors.Error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
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
    DownloadStatus.COMPLETED -> if (item.origin == DownloadOrigin.IMPORTED_EXTERNAL) {
        "Imported · ${formatBytes(item.verifiedBytes ?: item.bytesDownloaded)}"
    } else {
        "Downloaded · ${formatBytes(item.verifiedBytes ?: item.bytesDownloaded)}"
    }
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
