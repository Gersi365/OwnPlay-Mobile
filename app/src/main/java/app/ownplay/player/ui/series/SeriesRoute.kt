package app.ownplay.player.ui.series

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.download.OfflineDownloadSpec
import app.ownplay.player.onDemandPresentationSession
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.playback.OnDemandContentKind
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackRequest
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesDetails
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SERIES_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS = 1_000L

@Composable
internal fun SeriesRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String?,
    sourceKind: String?,
    requestedSeriesId: String? = null,
    onRequestedSeriesConsumed: () -> Unit = {},
    returnToLibraryOnDetailBack: Boolean = true,
    onReturnToLibrary: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val featureRuntime = remember(context) { SeriesFeatureRuntime(context.applicationContext) }
    val downloadRuntime = remember(context) { OfflineDownloadFeatureRuntime(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val onDemandPresentation by runtime.onDemandPresentationSession.state.collectAsState()

    DisposableEffect(featureRuntime, downloadRuntime) {
        onDispose {
            featureRuntime.close()
            downloadRuntime.close()
        }
    }

    if (sourceId == null) {
        SeriesDetailUnavailableState(
            title = "No playlist configured",
            body = "Add an Xtream playlist from Settings to load Series.",
            onAction = onOpenSettings,
        )
        return
    }
    if (sourceKind != SourceKinds.XTREAM) {
        SeriesDetailUnavailableState(
            title = "Series are unavailable for this source",
            body = "Series currently require an Xtream-compatible source.",
            onAction = onOpenSettings,
        )
        return
    }

    val catalog by featureRuntime.observeCatalog(sourceId).collectAsState(initial = SeriesCatalog())
    val downloads by downloadRuntime.observeAll().collectAsState(initial = emptyList())
    var selectedSeries by remember(sourceId) { mutableStateOf<SeriesSummary?>(null) }
    var details by remember(sourceId) { mutableStateOf<SeriesDetails?>(null) }
    var detailsLoading by remember(sourceId) { mutableStateOf(false) }
    var detailsError by remember(sourceId) { mutableStateOf<SourceError?>(null) }
    var selectedSeasonNumber by remember(sourceId) {
        mutableStateOf(onDemandPresentation.seriesSeasonNumber)
    }
    var selectedEpisodeId by remember(sourceId) {
        mutableStateOf(onDemandPresentation.seriesEpisodeId)
    }
    var refreshing by remember(sourceId) { mutableStateOf(true) }
    val detailsBackOwner = remember(sourceId) { Any() }
    val sessionSeriesPlayback = onDemandPresentation.seriesPlayback.takeIf {
        onDemandPresentation.kind == OnDemandContentKind.SERIES &&
            onDemandPresentation.sourceId == sourceId
    }
    val targetSeriesId = requestedSeriesId ?: onDemandPresentation.itemId.takeIf {
        onDemandPresentation.kind == OnDemandContentKind.SERIES &&
            onDemandPresentation.sourceId == sourceId
    }

    fun returnToLibrary() {
        runtime.onDemandPresentationSession.clear()
        onReturnToLibrary()
    }

    fun closeSeriesLevel() {
        when {
            selectedEpisodeId != null -> {
                selectedEpisodeId = null
                runtime.onDemandPresentationSession.updateSeriesSelection(
                    seasonNumber = selectedSeasonNumber,
                    episodeId = null,
                )
            }
            selectedSeasonNumber != null -> {
                selectedSeasonNumber = null
                selectedEpisodeId = null
                runtime.onDemandPresentationSession.updateSeriesSelection(
                    seasonNumber = null,
                    episodeId = null,
                )
            }
            else -> returnToLibrary()
        }
    }

    DisposableEffect(
        selectedSeries?.seriesId,
        selectedSeasonNumber,
        selectedEpisodeId,
        sessionSeriesPlayback?.episodeId,
        detailsBackOwner,
    ) {
        if (sessionSeriesPlayback == null) {
            PlaybackInteractionBridge.registerBackAction(detailsBackOwner, ::closeSeriesLevel)
        }
        onDispose { PlaybackInteractionBridge.clearBackAction(detailsBackOwner) }
    }

    LaunchedEffect(sourceId) {
        refreshing = true
        featureRuntime.refresh(sourceId)
        refreshing = false
    }

    LaunchedEffect(sourceId, targetSeriesId, catalog.series) {
        val seriesId = targetSeriesId ?: return@LaunchedEffect
        val target = catalog.series.firstOrNull { it.seriesId == seriesId } ?: return@LaunchedEffect
        selectedSeries = target
        if (requestedSeriesId == seriesId) onRequestedSeriesConsumed()
    }

    LaunchedEffect(
        onDemandPresentation.seriesSeasonNumber,
        onDemandPresentation.seriesEpisodeId,
        sessionSeriesPlayback?.episodeId,
    ) {
        if (sessionSeriesPlayback == null) {
            selectedSeasonNumber = onDemandPresentation.seriesSeasonNumber
            selectedEpisodeId = onDemandPresentation.seriesEpisodeId
        }
    }

    LaunchedEffect(selectedSeries?.seriesId) {
        val selected = selectedSeries
        if (selected == null) {
            details = null
            detailsError = null
            detailsLoading = false
            return@LaunchedEffect
        }
        detailsLoading = true
        detailsError = null
        val cachedDetails = featureRuntime.cachedDetails(sourceId, selected.seriesId)
        if (cachedDetails != null) details = cachedDetails
        when (val result = featureRuntime.details(sourceId, selected.seriesId)) {
            is SourceResult.Success -> details = result.value
            is SourceResult.Failure -> {
                if (cachedDetails == null) {
                    detailsError = result.error
                    details = null
                }
            }
        }
        detailsLoading = false
    }

    LaunchedEffect(details, selectedSeasonNumber, selectedEpisodeId) {
        val loadedDetails = details ?: return@LaunchedEffect
        val seasonNumber = selectedSeasonNumber
        if (seasonNumber != null) {
            val season = loadedDetails.seasons.firstOrNull { it.seasonNumber == seasonNumber }
            if (season == null) {
                selectedSeasonNumber = null
                selectedEpisodeId = null
                runtime.onDemandPresentationSession.updateSeriesSelection(null, null)
            } else if (
                selectedEpisodeId != null &&
                season.episodes.none { it.episodeId == selectedEpisodeId }
            ) {
                selectedEpisodeId = null
                runtime.onDemandPresentationSession.updateSeriesSelection(seasonNumber, null)
            }
        } else if (selectedEpisodeId != null) {
            selectedEpisodeId = null
            runtime.onDemandPresentationSession.updateSeriesSelection(null, null)
        }
    }

    val currentEpisode = sessionSeriesPlayback
    if (currentEpisode != null) {
        SeriesPlaybackScreen(
            runtime = runtime,
            featureRuntime = featureRuntime,
            sourceId = sourceId,
            episode = currentEpisode,
            onExit = { runtime.onDemandPresentationSession.returnFromSeriesPlayback() },
            onFullscreenStateChanged = onFullscreenStateChanged,
        )
        return
    }

    val selected = selectedSeries
    if (selected == null) {
        if (refreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            SeriesDetailUnavailableState(
                title = "Series unavailable",
                body = "Return to Library and choose a Series.",
                onAction = ::returnToLibrary,
            )
        }
        return
    }

    fun playEpisode(episode: SeriesEpisode) {
        runtime.playbackController.start(
            PlaybackRequest(
                sourceId = sourceId,
                channelId = episode.episodeId,
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = episode.providerEpisodeId,
                containerExtension = episode.containerExtension,
            ),
        )
        runtime.onDemandPresentationSession.showSeriesPlayback(
            sourceId = sourceId,
            episode = episode,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            selectedSeasonNumber = selectedSeasonNumber,
            selectedEpisodeId = selectedEpisodeId,
        )
    }

    fun retryDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.retry(download.downloadId) }
    }

    fun removeDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.remove(download.downloadId) }
    }

    SeriesDetailsPane(
        selected = selected,
        details = details,
        loading = detailsLoading,
        error = detailsError,
        selectedSeasonNumber = selectedSeasonNumber,
        selectedEpisodeId = selectedEpisodeId,
        downloads = downloads,
        focusBackOnEntry = true,
        onSeasonSelected = {
            selectedSeasonNumber = it
            selectedEpisodeId = null
            runtime.onDemandPresentationSession.updateSeriesSelection(it, null)
        },
        onEpisodeSelected = {
            selectedEpisodeId = it
            runtime.onDemandPresentationSession.updateSeriesSelection(selectedSeasonNumber, it)
        },
        onFavoriteChanged = { favorite ->
            scope.launch {
                if (featureRuntime.setFavorite(sourceId, selected.seriesId, favorite)) {
                    selectedSeries = selectedSeries?.copy(isFavorite = favorite)
                    details = details?.let { current ->
                        current.copy(series = current.series.copy(isFavorite = favorite))
                    }
                }
            }
        },
        onPlay = ::playEpisode,
        onDownload = { episode ->
            scope.launch {
                downloadRuntime.enqueue(
                    OfflineDownloadSpec(
                        sourceId = sourceId,
                        mediaKind = DownloadMediaKinds.SERIES_EPISODE,
                        contentId = episode.episodeId,
                        providerStreamId = episode.providerEpisodeId,
                        title = episode.title,
                        seriesTitle = episode.seriesTitle,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        posterUrl = episode.posterUrl,
                        containerExtension = episode.containerExtension,
                    ),
                )
            }
        },
        onPauseDownload = { scope.launch { downloadRuntime.pause(it.downloadId) } },
        onResumeDownload = { scope.launch { downloadRuntime.resume(it.downloadId) } },
        onRetryDownload = ::retryDownload,
        onRemoveDownload = ::removeDownload,
        onClearProgress = { episode ->
            scope.launch {
                featureRuntime.clearEpisodeProgress(sourceId, episode.episodeId)
            }
        },
        onClose = ::closeSeriesLevel,
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun SeriesPlaybackScreen(
    runtime: OwnPlayAppRuntime,
    featureRuntime: SeriesFeatureRuntime,
    sourceId: String,
    episode: SeriesEpisode,
    onExit: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val playbackState by runtime.playbackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var playerView by remember(episode.episodeId) { mutableStateOf<PlayerView?>(null) }
    var currentPosition by remember(episode.episodeId) {
        mutableStateOf(episode.positionMs ?: 0L)
    }
    var duration by remember(episode.episodeId) {
        mutableStateOf(episode.durationMs ?: 0L)
    }
    var exitRequested by remember(episode.episodeId) { mutableStateOf(false) }
    val backOwner = remember(episode.episodeId) { Any() }

    fun exitPlayback() {
        if (exitRequested) return
        exitRequested = true
        val view = playerView
        scope.launch {
            val player = view?.player
            if (player != null) {
                withTimeoutOrNull(SERIES_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS) {
                    featureRuntime.saveEpisodeProgress(
                        sourceId = sourceId,
                        episodeId = episode.episodeId,
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf {
                            it != C.TIME_UNSET && it > 0L
                        },
                    )
                }
            }
            runtime.playbackController.stopIfCurrent(
                sourceId = sourceId,
                channelId = episode.episodeId,
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
            )
            onFullscreenStateChanged(false)
            onExit()
        }
    }

    DisposableEffect(backOwner) {
        onFullscreenStateChanged(true)
        PlaybackInteractionBridge.registerBackAction(backOwner, ::exitPlayback)
        onDispose { PlaybackInteractionBridge.clearBackAction(backOwner) }
    }

    LaunchedEffect(playerView, episode.episodeId) {
        delay(300L)
        val player = playerView?.player ?: return@LaunchedEffect
        val resumePosition = episode.positionMs?.takeIf {
            it > 0L && !episode.progressCompleted
        }
        if (resumePosition != null && player.currentPosition < 1_000L) {
            player.seekTo(resumePosition)
            currentPosition = resumePosition
        }
    }

    LaunchedEffect(playerView, episode.episodeId) {
        while (currentCoroutineContext().isActive) {
            delay(2_000L)
            val player = playerView?.player ?: continue
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf {
                it != C.TIME_UNSET && it > 0L
            } ?: duration
            featureRuntime.saveEpisodeProgress(
                sourceId = sourceId,
                episodeId = episode.episodeId,
                positionMs = currentPosition,
                durationMs = duration.takeIf { it > 0L },
            )
        }
    }

    app.ownplay.player.ui.OnDemandPlaybackSurface(
        runtime = runtime,
        contentKey = episode.episodeId,
        title = episode.title,
        playbackState = playbackState,
        currentPositionMs = currentPosition,
        durationMs = duration,
        exitRequested = exitRequested,
        onExit = ::exitPlayback,
        onPlayerViewAvailable = { playerView = it },
        onPlayerViewReleased = { if (playerView === it) playerView = null },
        onSeekPositionChanged = { currentPosition = it },
    )
}

@Composable
private fun SeriesDetailUnavailableState(
    title: String,
    body: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAction) { Text("Continue") }
        }
    }
}
