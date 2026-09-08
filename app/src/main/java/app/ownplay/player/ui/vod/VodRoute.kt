package app.ownplay.player.ui.vod

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
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import app.ownplay.player.vod.VodMovieDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val VOD_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS = 1_000L

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun VodRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String?,
    sourceKind: String?,
    requestedMovieId: String? = null,
    onRequestedMovieConsumed: () -> Unit = {},
    returnToLibraryOnDetailBack: Boolean = true,
    onReturnToLibrary: () -> Unit = {},
    onOpenLive: () -> Unit = {},
    onOpenSeries: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val featureRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
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
        OnDemandUnavailableState(
            title = "No playlist configured",
            body = "Add an Xtream playlist from Settings to load Movies.",
            onAction = onOpenSettings,
        )
        return
    }
    if (sourceKind != SourceKinds.XTREAM) {
        OnDemandUnavailableState(
            title = "Movies are unavailable for this source",
            body = "Movies currently require an Xtream-compatible source.",
            onAction = onOpenSettings,
        )
        return
    }

    val catalog by featureRuntime.observeCatalog(sourceId).collectAsState(initial = VodCatalog())
    val downloads by downloadRuntime.observeAll().collectAsState(initial = emptyList())
    var selectedMovie by remember(sourceId) { mutableStateOf<VodMovie?>(null) }
    var details by remember(sourceId) { mutableStateOf<VodMovieDetails?>(null) }
    var detailsLoading by remember(sourceId) { mutableStateOf(false) }
    var detailsError by remember(sourceId) { mutableStateOf<SourceError?>(null) }
    var refreshing by remember(sourceId) { mutableStateOf(true) }
    val detailsBackOwner = remember(sourceId) { Any() }
    val sessionMoviePlayback = onDemandPresentation.moviePlayback.takeIf {
        onDemandPresentation.kind == OnDemandContentKind.MOVIE &&
            onDemandPresentation.sourceId == sourceId
    }
    val targetMovieId = requestedMovieId ?: onDemandPresentation.itemId.takeIf {
        onDemandPresentation.kind == OnDemandContentKind.MOVIE &&
            onDemandPresentation.sourceId == sourceId
    }

    fun returnToLibrary() {
        runtime.onDemandPresentationSession.clear()
        onReturnToLibrary()
    }

    DisposableEffect(sessionMoviePlayback?.movieId, detailsBackOwner) {
        if (sessionMoviePlayback == null) {
            PlaybackInteractionBridge.registerBackAction(detailsBackOwner, ::returnToLibrary)
        }
        onDispose { PlaybackInteractionBridge.clearBackAction(detailsBackOwner) }
    }

    LaunchedEffect(sourceId) {
        refreshing = true
        featureRuntime.refresh(sourceId)
        refreshing = false
    }

    LaunchedEffect(sourceId, targetMovieId, catalog.movies) {
        val movieId = targetMovieId ?: return@LaunchedEffect
        val target = catalog.movies.firstOrNull { it.movieId == movieId } ?: return@LaunchedEffect
        selectedMovie = target
        if (requestedMovieId == movieId) onRequestedMovieConsumed()
    }

    LaunchedEffect(selectedMovie?.movieId) {
        val movie = selectedMovie
        if (movie == null) {
            details = null
            detailsError = null
            detailsLoading = false
            return@LaunchedEffect
        }
        detailsLoading = true
        detailsError = null
        details = when (val result = featureRuntime.details(sourceId, movie.movieId)) {
            is SourceResult.Success -> result.value.copy(
                movie = result.value.movie.copy(
                    isFavorite = movie.isFavorite,
                    positionMs = movie.positionMs,
                    durationMs = movie.durationMs ?: result.value.movie.durationMs,
                    progressCompleted = movie.progressCompleted,
                    progressUpdatedAtEpochMillis = movie.progressUpdatedAtEpochMillis,
                ),
            )
            is SourceResult.Failure -> {
                detailsError = result.error
                null
            }
        }
        detailsLoading = false
    }

    fun downloadFor(movie: VodMovie): OfflineDownload? = downloads.firstOrNull { download ->
        download.sourceId == sourceId &&
            download.mediaKind == DownloadMediaKinds.MOVIE &&
            download.contentId == movie.movieId
    }

    fun setFavorite(movie: VodMovie, favorite: Boolean) {
        scope.launch {
            if (!featureRuntime.setFavorite(sourceId, movie.movieId, favorite)) return@launch
            selectedMovie = selectedMovie?.copy(isFavorite = favorite)
            details = details?.let { current ->
                current.copy(movie = current.movie.copy(isFavorite = favorite))
            }
        }
    }

    fun retryDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.retry(download.downloadId) }
    }

    fun removeDownload(download: OfflineDownload) {
        scope.launch { downloadRuntime.remove(download.downloadId) }
    }

    val movieToPlay = sessionMoviePlayback
    if (movieToPlay != null) {
        VodPlaybackScreen(
            runtime = runtime,
            featureRuntime = featureRuntime,
            sourceId = sourceId,
            movie = movieToPlay,
            onExit = { runtime.onDemandPresentationSession.returnFromMoviePlayback() },
            onFullscreenStateChanged = onFullscreenStateChanged,
        )
        return
    }

    val movie = selectedMovie
    if (movie == null) {
        if (refreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            OnDemandUnavailableState(
                title = "Movie unavailable",
                body = "Return to Library and choose a Movie.",
                onAction = ::returnToLibrary,
            )
        }
        return
    }

    MovieDetailsPane(
        movie = movie,
        details = details,
        loading = detailsLoading,
        error = detailsError,
        download = downloadFor(movie),
        focusBackOnEntry = true,
        onDismiss = ::returnToLibrary,
        onFavoriteChanged = { setFavorite(movie, it) },
        onDownload = { target ->
            scope.launch {
                downloadRuntime.enqueue(
                    OfflineDownloadSpec(
                        sourceId = sourceId,
                        mediaKind = DownloadMediaKinds.MOVIE,
                        contentId = target.movieId,
                        providerStreamId = target.providerStreamId,
                        title = target.name,
                        posterUrl = target.posterUrl,
                        containerExtension = target.containerExtension,
                    ),
                )
            }
        },
        onPauseDownload = { scope.launch { downloadRuntime.pause(it.downloadId) } },
        onResumeDownload = { scope.launch { downloadRuntime.resume(it.downloadId) } },
        onRetryDownload = ::retryDownload,
        onRemoveDownload = ::removeDownload,
        onClearProgress = {
            scope.launch { featureRuntime.clearProgress(sourceId, movie.movieId) }
        },
        onPlay = { target ->
            runtime.playbackController.start(
                PlaybackRequest(
                    sourceId = sourceId,
                    channelId = target.movieId,
                    mediaKind = PlaybackMediaKind.MOVIE,
                ),
            )
            runtime.onDemandPresentationSession.showMoviePlayback(
                sourceId = sourceId,
                movie = target,
                returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun VodPlaybackScreen(
    runtime: OwnPlayAppRuntime,
    featureRuntime: VodFeatureRuntime,
    sourceId: String,
    movie: VodMovie,
    onExit: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val playbackState by runtime.playbackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val backOwner = remember(movie.movieId) { Any() }
    var playerView by remember(movie.movieId) { mutableStateOf<PlayerView?>(null) }
    var currentPosition by remember(movie.movieId) { mutableStateOf(movie.positionMs ?: 0L) }
    var duration by remember(movie.movieId) { mutableStateOf(movie.durationMs ?: 0L) }
    var resumeApplied by remember(movie.movieId) { mutableStateOf(false) }
    var exitRequested by remember(movie.movieId) { mutableStateOf(false) }

    fun exitPlayback() {
        if (exitRequested) return
        exitRequested = true
        val lastPosition = currentPosition
        val lastDuration = duration.takeIf { it > 0L }
        scope.launch {
            withTimeoutOrNull(VOD_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS) {
                featureRuntime.saveProgress(
                    sourceId = sourceId,
                    movieId = movie.movieId,
                    positionMs = lastPosition,
                    durationMs = lastDuration,
                )
            }
            runtime.playbackController.stopIfCurrent(
                sourceId = sourceId,
                channelId = movie.movieId,
                mediaKind = PlaybackMediaKind.MOVIE,
            )
            onFullscreenStateChanged(false)
            onExit()
        }
    }

    DisposableEffect(movie.movieId, backOwner) {
        onFullscreenStateChanged(true)
        PlaybackInteractionBridge.registerBackAction(backOwner, ::exitPlayback)
        onDispose { PlaybackInteractionBridge.clearBackAction(backOwner) }
    }

    LaunchedEffect(playbackState, playerView, movie.movieId) {
        val stateRequest = when (val state = playbackState) {
            is PlaybackState.Playing -> state.request
            is PlaybackState.Paused -> state.request
            else -> null
        }
        if (
            !resumeApplied &&
            stateRequest?.mediaKind == PlaybackMediaKind.MOVIE &&
            stateRequest.channelId == movie.movieId
        ) {
            val player = playerView?.player ?: return@LaunchedEffect
            val resumePosition = movie.positionMs?.takeIf {
                it > 5_000L && !movie.progressCompleted
            }
            if (resumePosition != null && player.currentPosition < 1_000L) {
                player.seekTo(resumePosition)
                currentPosition = resumePosition
            } else {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
            }
            duration = player.duration.takeIf { it > 0L } ?: duration
            resumeApplied = true
        }
    }

    LaunchedEffect(playerView, movie.movieId) {
        var saveTick = 0
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            val player = playerView?.player ?: continue
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: duration
            if (++saveTick >= 5) {
                saveTick = 0
                featureRuntime.saveProgress(
                    sourceId = sourceId,
                    movieId = movie.movieId,
                    positionMs = currentPosition,
                    durationMs = duration.takeIf { it > 0L },
                )
            }
        }
    }

    app.ownplay.player.ui.OnDemandPlaybackSurface(
        runtime = runtime,
        contentKey = movie.movieId,
        title = movie.name,
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
private fun OnDemandUnavailableState(
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
