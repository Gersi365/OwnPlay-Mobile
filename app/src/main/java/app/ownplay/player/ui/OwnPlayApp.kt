package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.playback.LiveFullscreenEntryReason
import app.ownplay.player.playback.LivePlaybackPresentationPolicy
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.LivePlaybackSurfaceTeardown
import app.ownplay.player.playback.LivePlaybackTransitionGate
import app.ownplay.player.playback.LivePlaybackTransitionTarget
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.source.SourceSyncStage
import app.ownplay.player.source.SourceSyncState
import app.ownplay.player.source.selection.ActivePlaylistSelection
import app.ownplay.player.source.selection.ActivePlaylistStore
import app.ownplay.player.source.selection.resolveActivePlaylistId
import app.ownplay.player.ui.library.UnifiedLibraryRoute
import app.ownplay.player.ui.series.SeriesRoute
import app.ownplay.player.ui.vod.VodRoute
import kotlinx.coroutines.launch

private const val SECTION_MOTION_MILLIS = 200

private enum class OwnPlaySection {
    LIVE,
    MOVIES,
    SERIES,
    LIBRARY,
    SETTINGS,
}

@Composable
fun OwnPlayApp(
    runtime: OwnPlayAppRuntime,
    rotationFullscreenEnabled: Boolean = false,
    onPlaybackFullscreenChanged: (Boolean) -> Unit = {},
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit = {},
    onLivePreviewActiveChanged: (Boolean) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activePlaylistStore = remember(context) {
        ActivePlaylistStore(context.applicationContext)
    }
    val activePlaylistSelection by activePlaylistStore.observe().collectAsState(
        initial = ActivePlaylistSelection.Loading,
    )
    val activePlaylistScope = rememberCoroutineScope()
    val summaries by runtime.observeSourceSummaries().collectAsState(initial = emptyList())
    val syncState by runtime.sourceSyncState.collectAsState()
    val playbackState by runtime.playbackController.state.collectAsState()
    val playbackOrigin by runtime.playbackController.resolvedOrigin.collectAsState()
    val playbackTrackState by runtime.playbackTrackController.state.collectAsState()

    var section by remember { mutableStateOf(OwnPlaySection.LIVE) }
    var activeSourceId by remember { mutableStateOf<String?>(null) }
    var activeSelection by remember { mutableStateOf<LivePlaybackSelection?>(null) }
    var fullscreenSelection by remember { mutableStateOf<LivePlaybackSelection?>(null) }
    var fullscreenEntryReason by remember { mutableStateOf<LiveFullscreenEntryReason?>(null) }
    var requestedVodMovieId by remember { mutableStateOf<String?>(null) }
    var requestedSeriesId by remember { mutableStateOf<String?>(null) }
    var movieDetailReturnToLibrary by remember { mutableStateOf(false) }
    var seriesDetailReturnToLibrary by remember { mutableStateOf(false) }
    var vodFullscreen by remember { mutableStateOf(false) }
    var seriesFullscreen by remember { mutableStateOf(false) }
    var libraryFullscreen by remember { mutableStateOf(false) }
    val liveTransitionGate = remember { LivePlaybackTransitionGate() }

    fun rememberActiveSource(sourceId: String?) {
        activeSourceId = sourceId
        activePlaylistScope.launch {
            activePlaylistStore.set(sourceId)
        }
    }

    fun openLiveFullscreen(
        selection: LivePlaybackSelection,
        reason: LiveFullscreenEntryReason,
    ) {
        liveTransitionGate.requestHandoff(
            target = LivePlaybackTransitionTarget.fullscreen(selection),
            detachCurrentSurface = {
                PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)
            },
            stopPlayback = runtime.playbackController::stop,
            switchPresentation = {
                activeSelection = selection
                fullscreenEntryReason = reason
                fullscreenSelection = selection
            },
            startPlayback = { runtime.playbackController.start(selection.request) },
        )
    }

    fun returnLiveToPreview(selection: LivePlaybackSelection) {
        liveTransitionGate.requestHandoff(
            target = LivePlaybackTransitionTarget.preview(selection),
            detachCurrentSurface = {
                PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)
            },
            stopPlayback = runtime.playbackController::stop,
            switchPresentation = {
                rememberActiveSource(selection.request.sourceId)
                section = OwnPlaySection.LIVE
                activeSelection = selection
                fullscreenSelection = null
                fullscreenEntryReason = null
            },
            startPlayback = { runtime.playbackController.start(selection.request) },
        )
    }

    fun stopLivePlaybackSurface(clearPresentation: () -> Unit) {
        LivePlaybackSurfaceTeardown.stopAfterDetaching(
            detachCurrentSurface = {
                PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)
            },
            stopPlayback = runtime.playbackController::stop,
            clearPresentation = clearPresentation,
        )
    }

    LaunchedEffect(summaries, activePlaylistSelection) {
        val persistedSelection = activePlaylistSelection as? ActivePlaylistSelection.Ready
            ?: return@LaunchedEffect
        val enabledSourceIds = summaries
            .asSequence()
            .filter { summary -> summary.enabled }
            .map { summary -> summary.sourceId }
            .toList()
        val resolvedSourceId = resolveActivePlaylistId(
            persistedSourceId = persistedSelection.sourceId,
            currentSourceId = activeSourceId,
            enabledSourceIds = enabledSourceIds,
        )
        activeSourceId = resolvedSourceId

        if (enabledSourceIds.isNotEmpty() && persistedSelection.sourceId != resolvedSourceId) {
            activePlaylistStore.set(resolvedSourceId)
        }

        val selectionSourceId = activeSelection?.request?.sourceId
        if (selectionSourceId != null && selectionSourceId != resolvedSourceId) {
            stopLivePlaybackSurface {
                activeSelection = null
                fullscreenSelection = null
                fullscreenEntryReason = null
            }
        }
        if (resolvedSourceId == null) {
            requestedVodMovieId = null
            requestedSeriesId = null
            movieDetailReturnToLibrary = false
            seriesDetailReturnToLibrary = false
        }
    }

    fun openContentSection(target: OwnPlaySection) {
        if (target != OwnPlaySection.LIVE && activeSelection != null) {
            stopLivePlaybackSurface {
                activeSelection = null
                fullscreenSelection = null
                fullscreenEntryReason = null
            }
        }
        if (target != OwnPlaySection.MOVIES) {
            requestedVodMovieId = null
            movieDetailReturnToLibrary = false
        }
        if (target != OwnPlaySection.SERIES) {
            requestedSeriesId = null
            seriesDetailReturnToLibrary = false
        }
        section = target
    }

    val librarySectionActive =
        section == OwnPlaySection.LIBRARY ||
            section == OwnPlaySection.MOVIES ||
            section == OwnPlaySection.SERIES
    val previewActive =
        section == OwnPlaySection.LIVE &&
            activeSelection != null &&
            fullscreenSelection == null
    val playbackSurfaceActive =
        previewActive ||
            fullscreenSelection != null ||
            vodFullscreen ||
            seriesFullscreen ||
            libraryFullscreen
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val contentLandscape =
        isLandscape &&
            (section == OwnPlaySection.LIVE || section == OwnPlaySection.MOVIES)
    val observedLiveTransitionTarget =
        fullscreenSelection?.let(LivePlaybackTransitionTarget::fullscreen)
            ?: if (previewActive) {
                activeSelection?.let(LivePlaybackTransitionTarget::preview)
            } else {
                null
            }

    SideEffect {
        liveTransitionGate.reconcileObserved(observedLiveTransitionTarget)
    }

    LaunchedEffect(playbackSurfaceActive) {
        onPlaybackSurfaceActiveChanged(playbackSurfaceActive)
    }

    LaunchedEffect(previewActive, rotationFullscreenEnabled) {
        onLivePreviewActiveChanged(previewActive && rotationFullscreenEnabled)
    }

    LaunchedEffect(fullscreenSelection != null) {
        onPlaybackFullscreenChanged(fullscreenSelection != null)
    }

    LaunchedEffect(
        rotationFullscreenEnabled,
        isLandscape,
        isPortrait,
        activeSelection?.request?.channelId,
        fullscreenSelection?.request?.channelId,
        fullscreenEntryReason,
    ) {
        val selected = activeSelection
        if (
            LivePlaybackPresentationPolicy.shouldEnterFullscreenFromRotation(
                rotationFullscreenEnabled = rotationFullscreenEnabled,
                isLandscape = isLandscape,
                hasSelection = selected != null,
                alreadyFullscreen = fullscreenSelection != null,
            )
        ) {
            selected?.let { selection ->
                openLiveFullscreen(selection, LiveFullscreenEntryReason.ROTATION)
            }
            return@LaunchedEffect
        }

        val opened = fullscreenSelection
        if (
            LivePlaybackPresentationPolicy.shouldReturnToPreviewFromRotation(
                rotationFullscreenEnabled = rotationFullscreenEnabled,
                isPortrait = isPortrait,
                entryReason = fullscreenEntryReason,
                isFullscreen = opened != null,
            )
        ) {
            opened?.let(::returnLiveToPreview)
        }
    }

    val openedFullscreen = fullscreenSelection
    if (openedFullscreen != null) {
        PlaybackScreen(
            selection = openedFullscreen,
            state = playbackState,
            trackState = playbackTrackState,
            videoOutput = runtime.playbackVideoOutput,
            onPlay = runtime.playbackController::play,
            onPause = runtime.playbackController::pause,
            onRetry = runtime.playbackController::retry,
            onAudioSelection = runtime.playbackTrackController::selectAudio,
            onSubtitleSelection = runtime.playbackTrackController::selectSubtitle,
            onNavigate = { direction ->
                (fullscreenSelection ?: openedFullscreen)
                    .navigate(direction)
                    ?.let { target ->
                        activeSelection = target
                        fullscreenSelection = target
                        runtime.playbackController.start(target.request)
                    }
            },
            onReturnToChannels = {
                returnLiveToPreview(
                    fullscreenSelection ?: activeSelection ?: openedFullscreen,
                )
            },
            onFullscreenStateChanged = {},
        )
        return
    }

    val activeSummary = summaries.firstOrNull { it.sourceId == activeSourceId && it.enabled }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            when {
                section == OwnPlaySection.SETTINGS -> SettingsHeader(
                    activeSourceName = activeSummary?.name,
                    syncState = syncState,
                )
                contentLandscape && !vodFullscreen -> NavigationBar(
                    modifier = Modifier.statusBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    NavigationBarItem(
                        selected = section == OwnPlaySection.LIVE,
                        onClick = { openContentSection(OwnPlaySection.LIVE) },
                        icon = { Icon(Icons.Filled.LiveTv, contentDescription = "Live") },
                        label = { Text("Live") },
                    )
                    NavigationBarItem(
                        selected = librarySectionActive,
                        onClick = { openContentSection(OwnPlaySection.LIBRARY) },
                        icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "Library") },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = section == OwnPlaySection.SETTINGS,
                        onClick = { openContentSection(OwnPlaySection.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
            }
        },
        bottomBar = {
            if (
                !contentLandscape &&
                !vodFullscreen &&
                !seriesFullscreen &&
                !libraryFullscreen
            ) {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    NavigationBarItem(
                        selected = section == OwnPlaySection.LIVE,
                        onClick = { openContentSection(OwnPlaySection.LIVE) },
                        icon = { Icon(Icons.Filled.LiveTv, contentDescription = "Live") },
                        label = { Text("Live") },
                    )
                    NavigationBarItem(
                        selected = librarySectionActive,
                        onClick = { openContentSection(OwnPlaySection.LIBRARY) },
                        icon = { Icon(Icons.Filled.VideoLibrary, contentDescription = "Library") },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = section == OwnPlaySection.SETTINGS,
                        onClick = { openContentSection(OwnPlaySection.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
        ) {
            AnimatedContent(
                targetState = section,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(
                            animationSpec = tween(SECTION_MOTION_MILLIS),
                            initialOffsetX = { width -> width / 18 },
                        ) + fadeIn(tween(SECTION_MOTION_MILLIS))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(SECTION_MOTION_MILLIS),
                                targetOffsetX = { width -> -width / 28 },
                            ) + fadeOut(tween(130)))
                    } else {
                        (slideInHorizontally(
                            animationSpec = tween(SECTION_MOTION_MILLIS),
                            initialOffsetX = { width -> -width / 18 },
                        ) + fadeIn(tween(SECTION_MOTION_MILLIS))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(SECTION_MOTION_MILLIS),
                                targetOffsetX = { width -> width / 28 },
                            ) + fadeOut(tween(130)))
                    }
                },
                label = "ownPlaySection",
            ) { targetSection ->
                when (targetSection) {
                    OwnPlaySection.LIVE -> {
                        val sourceId = activeSourceId
                        if (sourceId == null) {
                            LiveNoSourceScreen(
                                syncState = syncState,
                                onAddPlaylist = { section = OwnPlaySection.SETTINGS },
                            )
                        } else {
                            LiveRoute(
                                runtime = runtime,
                                sourceId = sourceId,
                                activeSelection = activeSelection,
                                playbackState = playbackState,
                                videoOutput = runtime.playbackVideoOutput,
                                syncState = syncState,
                                onPlay = runtime.playbackController::play,
                                onPause = runtime.playbackController::pause,
                                onRetry = runtime.playbackController::retry,
                                onOpenMovies = { openContentSection(OwnPlaySection.MOVIES) },
                                onOpenSeries = { openContentSection(OwnPlaySection.SERIES) },
                                onOpenSettings = { openContentSection(OwnPlaySection.SETTINGS) },
                                onPreviewRequested = { selection ->
                                    activeSelection = selection
                                    runtime.playbackController.start(selection.request)
                                },
                                onPreviewClosed = {
                                    stopLivePlaybackSurface {
                                        activeSelection = null
                                    }
                                },
                                onOpenFullscreen = { selection ->
                                    openLiveFullscreen(
                                        selection = activeSelection ?: selection,
                                        reason = LiveFullscreenEntryReason.USER,
                                    )
                                },
                                onNavigatePreview = { direction ->
                                    activeSelection
                                        ?.navigate(direction)
                                        ?.let { target ->
                                            activeSelection = target
                                            runtime.playbackController.start(target.request)
                                        }
                                },
                            )
                        }
                    }

                    OwnPlaySection.MOVIES -> VodRoute(
                        runtime = runtime,
                        sourceId = activeSourceId,
                        sourceKind = activeSummary?.sourceKind,
                        requestedMovieId = requestedVodMovieId,
                        onRequestedMovieConsumed = { requestedVodMovieId = null },
                        returnToLibraryOnDetailBack = movieDetailReturnToLibrary,
                        onReturnToLibrary = { openContentSection(OwnPlaySection.LIBRARY) },
                        onOpenLive = { openContentSection(OwnPlaySection.LIVE) },
                        onOpenSeries = { openContentSection(OwnPlaySection.SERIES) },
                        onOpenSettings = { openContentSection(OwnPlaySection.SETTINGS) },
                        onFullscreenStateChanged = { fullscreen ->
                            vodFullscreen = fullscreen
                            onPlaybackFullscreenChanged(fullscreen)
                        },
                    )

                    OwnPlaySection.SERIES -> SeriesRoute(
                        runtime = runtime,
                        sourceId = activeSourceId,
                        sourceKind = activeSummary?.sourceKind,
                        requestedSeriesId = requestedSeriesId,
                        onRequestedSeriesConsumed = { requestedSeriesId = null },
                        returnToLibraryOnDetailBack = seriesDetailReturnToLibrary,
                        onReturnToLibrary = { openContentSection(OwnPlaySection.LIBRARY) },
                        onOpenSettings = { openContentSection(OwnPlaySection.SETTINGS) },
                        onFullscreenStateChanged = { fullscreen ->
                            seriesFullscreen = fullscreen
                            onPlaybackFullscreenChanged(fullscreen)
                        },
                    )

                    OwnPlaySection.LIBRARY -> UnifiedLibraryRoute(
                        runtime = runtime,
                        sourceId = activeSourceId,
                        sourceKind = activeSummary?.sourceKind,
                        onOpenMovieDetails = { sourceId, movieId ->
                            rememberActiveSource(sourceId)
                            requestedVodMovieId = movieId
                            movieDetailReturnToLibrary = true
                            openContentSection(OwnPlaySection.MOVIES)
                        },
                        onOpenSeriesDetails = { sourceId, seriesId ->
                            rememberActiveSource(sourceId)
                            requestedSeriesId = seriesId
                            seriesDetailReturnToLibrary = true
                            openContentSection(OwnPlaySection.SERIES)
                        },
                        onFullscreenStateChanged = { fullscreen ->
                            libraryFullscreen = fullscreen
                            onPlaybackFullscreenChanged(fullscreen)
                        },
                    )

                    OwnPlaySection.SETTINGS -> SettingsScreen(
                        runtime = runtime,
                        summaries = summaries,
                        syncState = syncState,
                        activeSourceName = activeSummary?.name,
                        hasActivePlayback =
                            activeSelection != null ||
                                vodFullscreen ||
                                seriesFullscreen ||
                                libraryFullscreen,
                        onOpenLive = { section = OwnPlaySection.LIVE },
                        onOpenSourceInLive = { sourceId ->
                            if (sourceId != activeSourceId) {
                                if (activeSelection != null || fullscreenSelection != null) {
                                    stopLivePlaybackSurface {
                                        activeSelection = null
                                        fullscreenSelection = null
                                        fullscreenEntryReason = null
                                    }
                                }
                            }
                            rememberActiveSource(sourceId)
                            section = OwnPlaySection.LIVE
                        },
                        onStopPlayback = {
                            if (activeSelection != null || fullscreenSelection != null) {
                                stopLivePlaybackSurface {
                                    activeSelection = null
                                    fullscreenSelection = null
                                    fullscreenEntryReason = null
                                }
                            } else {
                                runtime.playbackController.stop()
                            }
                        },
                    )
                }
            }

            val resolvedOrigin = playbackOrigin
            if (
                resolvedOrigin != null &&
                (vodFullscreen || seriesFullscreen || libraryFullscreen)
            ) {
                PlaybackOriginBadge(
                    origin = resolvedOrigin,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    activeSourceName: String?,
    syncState: SourceSyncState,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = "OP",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OwnPlay",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = activeSourceName?.let { "Settings · $it" } ?: "Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (syncState.stage) {
                SourceSyncStage.LoadingChannels,
                SourceSyncStage.LoadingEpg,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun LiveNoSourceScreen(
    syncState: SourceSyncState,
    onAddPlaylist: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Live TV",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (syncState.stage == SourceSyncStage.LoadingChannels) {
                    Text(
                        text = "Loading channels…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Text(
                        text = "No playlist configured",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Add a playlist from Settings to start watching Live TV.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAddPlaylist) { Text("Add playlist") }
                }
            }
        }
    }
}