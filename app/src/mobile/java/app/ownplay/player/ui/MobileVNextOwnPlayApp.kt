package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.livePlaybackPresentationSession
import app.ownplay.player.onDemandPresentationSession
import app.ownplay.player.playback.LiveFullscreenEntryReason
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.LivePlaybackSurfaceTeardown
import app.ownplay.player.playback.LivePlaybackTransitionGate
import app.ownplay.player.playback.LivePlaybackTransitionTarget
import app.ownplay.player.playback.OnDemandContentKind
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.source.SourceSyncState
import app.ownplay.player.source.selection.ActivePlaylistSelection
import app.ownplay.player.source.selection.ActivePlaylistStore
import app.ownplay.player.source.selection.resolveActivePlaylistId
import app.ownplay.player.ui.library.UnifiedLibraryRoute
import app.ownplay.player.ui.series.SeriesRoute
import app.ownplay.player.ui.theme.OwnPlaySpacing
import app.ownplay.player.ui.vod.VodRoute
import kotlinx.coroutines.launch

/**
 * vNext Mobile presentation shell.
 *
 * Primary destinations are Live / Library / Settings. Downloads remains available from Settings.
 * Playback/session ownership remains delegated to the established controllers and presentation
 * sessions.
 */
@Composable
internal fun MobileVNextOwnPlayApp(
    runtime: OwnPlayAppRuntime,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
) {
    MobileVNextConfigurationBoundary {
        MobileVNextOwnPlayAppContent(
            runtime = runtime,
            onPlaybackFullscreenChanged = onPlaybackFullscreenChanged,
            onPlaybackSurfaceActiveChanged = onPlaybackSurfaceActiveChanged,
        )
    }
}

@Composable
private fun MobileVNextOwnPlayAppContent(
    runtime: OwnPlayAppRuntime,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
) {
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
    val playbackTrackState by runtime.playbackTrackController.state.collectAsState()
    val livePresentation by runtime.livePlaybackPresentationSession.state.collectAsState()
    val onDemandPresentation by runtime.onDemandPresentationSession.state.collectAsState()

    var navigation by remember {
        val initialDestination = when (onDemandPresentation.kind) {
            OnDemandContentKind.MOVIE -> MobileShellDestination.MOVIES
            OnDemandContentKind.SERIES -> MobileShellDestination.SERIES
            null -> MobileShellDestination.LIVE
        }
        mutableStateOf(MobileShellNavigationState.initial(initialDestination))
    }
    val section = navigation.destination
    var activeSourceId by remember { mutableStateOf(onDemandPresentation.sourceId) }
    var requestedVodMovieId by remember {
        mutableStateOf(
            onDemandPresentation.itemId.takeIf {
                onDemandPresentation.kind == OnDemandContentKind.MOVIE
            },
        )
    }
    var requestedSeriesId by remember {
        mutableStateOf(
            onDemandPresentation.itemId.takeIf {
                onDemandPresentation.kind == OnDemandContentKind.SERIES
            },
        )
    }
    var movieDetailReturnToLibrary by remember {
        mutableStateOf(
            onDemandPresentation.kind == OnDemandContentKind.MOVIE &&
                onDemandPresentation.returnToLibraryOnDetailBack,
        )
    }
    var seriesDetailReturnToLibrary by remember {
        mutableStateOf(
            onDemandPresentation.kind == OnDemandContentKind.SERIES &&
                onDemandPresentation.returnToLibraryOnDetailBack,
        )
    }
    var libraryFullscreen by remember { mutableStateOf(false) }
    val vodFullscreen = onDemandPresentation.isMoviePlayback
    val seriesFullscreen = onDemandPresentation.isSeriesPlayback
    val activeSelection = livePresentation.selection
    val fullscreenSelection = livePresentation.fullscreenSelection
    val liveTransitionGate = remember { LivePlaybackTransitionGate() }

    fun rememberActiveSource(sourceId: String?) {
        activeSourceId = sourceId
        activePlaylistScope.launch {
            activePlaylistStore.set(sourceId)
        }
    }

    fun stopLivePresentation(clearPresentation: () -> Unit) {
        LivePlaybackSurfaceTeardown.stopAfterDetaching(
            detachCurrentSurface = {
                PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)
            },
            stopPlayback = runtime.playbackController::stop,
            clearPresentation = clearPresentation,
        )
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
                runtime.livePlaybackPresentationSession.showFullscreen(
                    selection = selection,
                    entryReason = reason,
                )
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
                navigation = navigation.open(MobileShellDestination.LIVE)
                runtime.livePlaybackPresentationSession.showPreview(selection)
            },
            startPlayback = { runtime.playbackController.start(selection.request) },
        )
    }

    fun openSection(target: MobileShellDestination) {
        if (target != MobileShellDestination.LIVE && activeSelection != null) {
            stopLivePresentation {
                runtime.livePlaybackPresentationSession.clear()
            }
        }

        val onDemandCurrent = runtime.onDemandPresentationSession.current
        when (target) {
            MobileShellDestination.MOVIES -> {
                if (onDemandCurrent.kind != OnDemandContentKind.MOVIE) {
                    activeSourceId?.let(runtime.onDemandPresentationSession::showMovieCatalog)
                }
            }
            MobileShellDestination.SERIES -> {
                if (onDemandCurrent.kind != OnDemandContentKind.SERIES) {
                    activeSourceId?.let(runtime.onDemandPresentationSession::showSeriesCatalog)
                }
            }
            else -> if (onDemandCurrent.kind != null) {
                runtime.onDemandPresentationSession.clear()
            }
        }

        if (target != MobileShellDestination.MOVIES) {
            requestedVodMovieId = null
            movieDetailReturnToLibrary = false
        }
        if (target != MobileShellDestination.SERIES) {
            requestedSeriesId = null
            seriesDetailReturnToLibrary = false
        }
        navigation = navigation.open(target)
    }

    BackHandler(enabled = section != MobileShellDestination.LIVE) {
        val interactionHandled = when (section) {
            MobileShellDestination.LIBRARY,
            MobileShellDestination.MOVIES,
            MobileShellDestination.SERIES,
            -> PlaybackInteractionBridge.handleBack()
            MobileShellDestination.LIVE,
            MobileShellDestination.SETTINGS,
            -> false
        }
        if (interactionHandled) return@BackHandler

        when (section) {
            MobileShellDestination.MOVIES,
            MobileShellDestination.SERIES,
            -> openSection(MobileShellDestination.LIBRARY)
            MobileShellDestination.LIBRARY,
            MobileShellDestination.SETTINGS,
            -> openSection(MobileShellDestination.LIVE)
            MobileShellDestination.LIVE -> Unit
        }
    }

    LaunchedEffect(summaries, activePlaylistSelection) {
        val persistedSelection = activePlaylistSelection as? ActivePlaylistSelection.Ready
            ?: return@LaunchedEffect
        val enabledSourceIds = summaries
            .asSequence()
            .filter { summary -> summary.enabled }
            .map { summary -> summary.sourceId }
            .toList()
        val previousSourceId = activeSourceId
        val resolvedSourceId = resolveActivePlaylistId(
            persistedSourceId = persistedSelection.sourceId,
            currentSourceId = activeSourceId,
            enabledSourceIds = enabledSourceIds,
        )
        activeSourceId = resolvedSourceId

        if (enabledSourceIds.isNotEmpty() && persistedSelection.sourceId != resolvedSourceId) {
            activePlaylistStore.set(resolvedSourceId)
        }
        if (resolvedSourceId != null && previousSourceId != resolvedSourceId) {
            runtime.onActiveSourceSelected(resolvedSourceId)
        }

        val selectionSourceId = activeSelection?.request?.sourceId
        if (selectionSourceId != null && selectionSourceId != resolvedSourceId) {
            stopLivePresentation {
                runtime.livePlaybackPresentationSession.clear()
            }
        }
        val onDemandSourceId = runtime.onDemandPresentationSession.current.sourceId
        if (
            enabledSourceIds.isNotEmpty() &&
            resolvedSourceId != null &&
            onDemandSourceId != null &&
            onDemandSourceId != resolvedSourceId
        ) {
            runtime.onDemandPresentationSession.clear()
        }
        if (resolvedSourceId == null) {
            requestedVodMovieId = null
            requestedSeriesId = null
            movieDetailReturnToLibrary = false
            seriesDetailReturnToLibrary = false
        }
    }

    val previewActive =
        section == MobileShellDestination.LIVE &&
            activeSelection != null &&
            fullscreenSelection == null
    val playbackSurfaceActive =
        previewActive ||
            fullscreenSelection != null ||
            vodFullscreen ||
            seriesFullscreen ||
            libraryFullscreen
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
    LaunchedEffect(fullscreenSelection != null) {
        onPlaybackFullscreenChanged(fullscreenSelection != null)
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
                        runtime.livePlaybackPresentationSession.replaceSelection(target)
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
    val shellFullscreen = vodFullscreen || seriesFullscreen || libraryFullscreen
    val showHeader = !shellFullscreen
    val showPrimaryNavigation = !shellFullscreen

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showHeader) {
                MobileVNextHeader()
            }
        },
        bottomBar = {
            if (showPrimaryNavigation) {
                MobileVNextPrimaryNavigationBar(
                    selectedDestination = section.primaryDestination(),
                    onOpenLive = { openSection(MobileShellDestination.LIVE) },
                    onOpenLibrary = { openSection(MobileShellDestination.LIBRARY) },
                    onOpenSettings = { openSection(MobileShellDestination.SETTINGS) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
        ) {
            when (section) {
                MobileShellDestination.LIVE -> {
                    val sourceId = activeSourceId
                    if (sourceId == null) {
                        MobileVNextNoSourceScreen(
                            syncState = syncState,
                            onAddPlaylist = { openSection(MobileShellDestination.SETTINGS) },
                            modifier = Modifier.fillMaxSize(),
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
                            onOpenMovies = { openSection(MobileShellDestination.MOVIES) },
                            onOpenSeries = { openSection(MobileShellDestination.SERIES) },
                            onOpenSettings = { openSection(MobileShellDestination.SETTINGS) },
                            onPreviewRequested = { selection ->
                                runtime.livePlaybackPresentationSession.showPreview(selection)
                                runtime.playbackController.start(selection.request)
                            },
                            onPreviewClosed = {
                                stopLivePresentation {
                                    runtime.livePlaybackPresentationSession.clear()
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
                                        runtime.livePlaybackPresentationSession.replaceSelection(target)
                                        runtime.playbackController.start(target.request)
                                    }
                            },
                        )
                    }
                }

                MobileShellDestination.LIBRARY -> UnifiedLibraryRoute(
                    runtime = runtime,
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    onOpenMovieDetails = { sourceId, movieId ->
                        rememberActiveSource(sourceId)
                        runtime.onDemandPresentationSession.showMovieDetail(
                            sourceId = sourceId,
                            movieId = movieId,
                            returnToLibraryOnDetailBack = true,
                        )
                        requestedVodMovieId = movieId
                        movieDetailReturnToLibrary = true
                        openSection(MobileShellDestination.MOVIES)
                    },
                    onOpenSeriesDetails = { sourceId, seriesId ->
                        rememberActiveSource(sourceId)
                        runtime.onDemandPresentationSession.showSeriesDetail(
                            sourceId = sourceId,
                            seriesId = seriesId,
                            returnToLibraryOnDetailBack = true,
                        )
                        requestedSeriesId = seriesId
                        seriesDetailReturnToLibrary = true
                        openSection(MobileShellDestination.SERIES)
                    },
                    onFullscreenStateChanged = { fullscreen ->
                        libraryFullscreen = fullscreen
                        onPlaybackFullscreenChanged(fullscreen)
                    },
                )

                MobileShellDestination.MOVIES -> VodRoute(
                    runtime = runtime,
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    requestedMovieId = requestedVodMovieId,
                    onRequestedMovieConsumed = { requestedVodMovieId = null },
                    returnToLibraryOnDetailBack = movieDetailReturnToLibrary,
                    onReturnToLibrary = { openSection(MobileShellDestination.LIBRARY) },
                    onOpenLive = { openSection(MobileShellDestination.LIVE) },
                    onOpenSeries = { openSection(MobileShellDestination.SERIES) },
                    onOpenSettings = { openSection(MobileShellDestination.SETTINGS) },
                    onFullscreenStateChanged = onPlaybackFullscreenChanged,
                )

                MobileShellDestination.SERIES -> SeriesRoute(
                    runtime = runtime,
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    requestedSeriesId = requestedSeriesId,
                    onRequestedSeriesConsumed = { requestedSeriesId = null },
                    returnToLibraryOnDetailBack = seriesDetailReturnToLibrary,
                    onReturnToLibrary = { openSection(MobileShellDestination.LIBRARY) },
                    onOpenSettings = { openSection(MobileShellDestination.SETTINGS) },
                    onFullscreenStateChanged = onPlaybackFullscreenChanged,
                )

                MobileShellDestination.SETTINGS -> SettingsScreen(
                    runtime = runtime,
                    summaries = summaries,
                    syncState = syncState,
                    activeSourceName = activeSummary?.name,
                    hasActivePlayback =
                        activeSelection != null ||
                            vodFullscreen ||
                            seriesFullscreen ||
                            libraryFullscreen,
                    onOpenLive = { openSection(MobileShellDestination.LIVE) },
                    onOpenSourceInLive = { sourceId ->
                        if (sourceId != activeSourceId && activeSelection != null) {
                            stopLivePresentation {
                                runtime.livePlaybackPresentationSession.clear()
                            }
                        }
                        rememberActiveSource(sourceId)
                        runtime.onDemandPresentationSession.clear()
                        navigation = navigation.open(MobileShellDestination.LIVE)
                    },
                    onStopPlayback = {
                        if (activeSelection != null || fullscreenSelection != null) {
                            stopLivePresentation {
                                runtime.livePlaybackPresentationSession.clear()
                            }
                        } else {
                            runtime.playbackController.stop()
                            runtime.onDemandPresentationSession.clear()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MobileVNextHeader() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = OwnPlaySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        ) {
            Text(
                text = "OwnPlay",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun MobileVNextPrimaryNavigationBar(
    selectedDestination: MobilePrimaryDestination?,
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        NavigationBar(
            modifier = Modifier.height(64.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = selectedDestination == MobilePrimaryDestination.LIVE,
                onClick = onOpenLive,
                icon = {
                    Icon(
                        Icons.Filled.LiveTv,
                        contentDescription = "Live",
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text("Live", style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = colors,
            )
            NavigationBarItem(
                selected = selectedDestination == MobilePrimaryDestination.LIBRARY,
                onClick = onOpenLibrary,
                icon = {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = "Library",
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text("Library", style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = colors,
            )
            NavigationBarItem(
                selected = selectedDestination == MobilePrimaryDestination.SETTINGS,
                onClick = onOpenSettings,
                icon = {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true,
                colors = colors,
            )
        }
    }
}

@Composable
private fun MobileVNextNoSourceScreen(
    syncState: SourceSyncState,
    onAddPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = syncState.sourceId != null
    Box(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = if (loading) "Preparing Live TV" else "No playlist configured",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (loading) {
                        "Loading channels from your active playlist…"
                    } else {
                        "Add an Xtream or M3U playlist in Settings to start watching."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (!loading) {
                    TextButton(onClick = onAddPlaylist) {
                        Text("Open Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileVNextConfigurationBoundary(content: @Composable () -> Unit) {
    val current = LocalConfiguration.current
    val mobileConfiguration = Configuration(current).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or
                Configuration.UI_MODE_TYPE_NORMAL
    }
    CompositionLocalProvider(
        LocalConfiguration provides mobileConfiguration,
        content = content,
    )
}
