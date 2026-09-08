package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.ownplay.player.ui.library.MobileLibraryRoute
import app.ownplay.player.ui.series.SeriesRoute
import app.ownplay.player.ui.vod.VodRoute
import kotlinx.coroutines.launch

private enum class MobileSection {
    HOME,
    LIVE,
    LIBRARY,
    DOWNLOADS,
    MOVIES,
    SERIES,
    SETTINGS,
}

/**
 * Mobile-only OwnPlay presentation shell.
 *
 * Primary navigation is Home / Live / Library / Downloads. Movies and Series are internal Library
 * routes. Settings is outside primary media navigation and is opened from contextual/header entry.
 */
@Composable
internal fun MobileOwnPlayApp(
    runtime: OwnPlayAppRuntime,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
) {
    MobileConfigurationBoundary {
        MobileOwnPlayAppContent(
            runtime = runtime,
            onPlaybackFullscreenChanged = onPlaybackFullscreenChanged,
            onPlaybackSurfaceActiveChanged = onPlaybackSurfaceActiveChanged,
        )
    }
}

@Composable
private fun MobileOwnPlayAppContent(
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
    val playbackOrigin by runtime.playbackController.resolvedOrigin.collectAsState()
    val playbackTrackState by runtime.playbackTrackController.state.collectAsState()
    val livePresentation by runtime.livePlaybackPresentationSession.state.collectAsState()
    val onDemandPresentation by runtime.onDemandPresentationSession.state.collectAsState()

    var section by remember {
        mutableStateOf(
            when (onDemandPresentation.kind) {
                OnDemandContentKind.MOVIE -> MobileSection.MOVIES
                OnDemandContentKind.SERIES -> MobileSection.SERIES
                null -> MobileSection.HOME
            },
        )
    }
    var settingsReturnSection by remember { mutableStateOf(MobileSection.HOME) }
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
                section = MobileSection.LIVE
                runtime.livePlaybackPresentationSession.showPreview(selection)
            },
            startPlayback = { runtime.playbackController.start(selection.request) },
        )
    }

    fun openSection(target: MobileSection) {
        if (target != MobileSection.LIVE && activeSelection != null) {
            stopLivePresentation {
                runtime.livePlaybackPresentationSession.clear()
            }
        }

        val onDemandCurrent = runtime.onDemandPresentationSession.current
        when (target) {
            MobileSection.MOVIES -> {
                if (onDemandCurrent.kind != OnDemandContentKind.MOVIE) {
                    activeSourceId?.let(runtime.onDemandPresentationSession::showMovieCatalog)
                }
            }
            MobileSection.SERIES -> {
                if (onDemandCurrent.kind != OnDemandContentKind.SERIES) {
                    activeSourceId?.let(runtime.onDemandPresentationSession::showSeriesCatalog)
                }
            }
            else -> if (onDemandCurrent.kind != null) {
                runtime.onDemandPresentationSession.clear()
            }
        }

        if (target != MobileSection.MOVIES) {
            requestedVodMovieId = null
            movieDetailReturnToLibrary = false
        }
        if (target != MobileSection.SERIES) {
            requestedSeriesId = null
            seriesDetailReturnToLibrary = false
        }
        section = target
    }

    fun openSettings() {
        settingsReturnSection = when (section) {
            MobileSection.MOVIES,
            MobileSection.SERIES,
            -> MobileSection.LIBRARY
            MobileSection.SETTINGS -> settingsReturnSection
            else -> section
        }
        openSection(MobileSection.SETTINGS)
    }

    BackHandler(enabled = section != MobileSection.HOME) {
        val interactionHandled = when (section) {
            MobileSection.LIBRARY,
            MobileSection.MOVIES,
            MobileSection.SERIES,
            -> PlaybackInteractionBridge.handleBack()
            MobileSection.HOME,
            MobileSection.LIVE,
            MobileSection.DOWNLOADS,
            MobileSection.SETTINGS,
            -> false
        }
        if (interactionHandled) return@BackHandler

        when (section) {
            MobileSection.MOVIES,
            MobileSection.SERIES,
            -> openSection(MobileSection.LIBRARY)
            MobileSection.SETTINGS -> openSection(settingsReturnSection)
            MobileSection.LIVE,
            MobileSection.LIBRARY,
            MobileSection.DOWNLOADS,
            -> openSection(MobileSection.HOME)
            MobileSection.HOME -> Unit
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
        section == MobileSection.LIVE &&
            activeSelection != null &&
            fullscreenSelection == null
    val playbackSurfaceActive =
        previewActive ||
            fullscreenSelection != null ||
            vodFullscreen ||
            seriesFullscreen
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
    val librarySectionActive =
        section == MobileSection.LIBRARY ||
            section == MobileSection.MOVIES ||
            section == MobileSection.SERIES
    val hidePrimaryNavigation =
        vodFullscreen || seriesFullscreen || section == MobileSection.SETTINGS
    val showShellHeader =
        section == MobileSection.HOME ||
            section == MobileSection.LIBRARY ||
            section == MobileSection.DOWNLOADS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showShellHeader) {
                MobileAppHeader(onOpenSettings = ::openSettings)
            }
        },
        bottomBar = {
            if (!hidePrimaryNavigation) {
                MobilePrimaryNavigationBar(
                    homeSelected = section == MobileSection.HOME,
                    liveSelected = section == MobileSection.LIVE,
                    librarySelected = librarySectionActive,
                    downloadsSelected = section == MobileSection.DOWNLOADS,
                    onOpenHome = { openSection(MobileSection.HOME) },
                    onOpenLive = { openSection(MobileSection.LIVE) },
                    onOpenLibrary = { openSection(MobileSection.LIBRARY) },
                    onOpenDownloads = { openSection(MobileSection.DOWNLOADS) },
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
                MobileSection.HOME -> MobileHomeScreen(
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    onOpenSettings = ::openSettings,
                    onOpenMovieDetails = { sourceId, movieId ->
                        rememberActiveSource(sourceId)
                        runtime.onDemandPresentationSession.showMovieDetail(
                            sourceId = sourceId,
                            movieId = movieId,
                            returnToLibraryOnDetailBack = true,
                        )
                        requestedVodMovieId = movieId
                        movieDetailReturnToLibrary = true
                        openSection(MobileSection.MOVIES)
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
                        openSection(MobileSection.SERIES)
                    },
                )

                MobileSection.LIVE -> {
                    val sourceId = activeSourceId
                    if (sourceId == null) {
                        MobileNoSourceScreen(
                            syncState = syncState,
                            onAddPlaylist = ::openSettings,
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
                            onOpenSettings = ::openSettings,
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

                MobileSection.LIBRARY -> MobileLibraryRoute(
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
                        openSection(MobileSection.MOVIES)
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
                        openSection(MobileSection.SERIES)
                    },
                )

                MobileSection.DOWNLOADS -> DownloadsSettingsScreen()

                MobileSection.MOVIES -> VodRoute(
                    runtime = runtime,
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    requestedMovieId = requestedVodMovieId,
                    onRequestedMovieConsumed = { requestedVodMovieId = null },
                    returnToLibraryOnDetailBack = movieDetailReturnToLibrary,
                    onReturnToLibrary = { openSection(MobileSection.LIBRARY) },
                    onOpenLive = { openSection(MobileSection.LIVE) },
                    onOpenSeries = { openSection(MobileSection.SERIES) },
                    onOpenSettings = ::openSettings,
                    onFullscreenStateChanged = onPlaybackFullscreenChanged,
                )

                MobileSection.SERIES -> SeriesRoute(
                    runtime = runtime,
                    sourceId = activeSourceId,
                    sourceKind = activeSummary?.sourceKind,
                    requestedSeriesId = requestedSeriesId,
                    onRequestedSeriesConsumed = { requestedSeriesId = null },
                    returnToLibraryOnDetailBack = seriesDetailReturnToLibrary,
                    onReturnToLibrary = { openSection(MobileSection.LIBRARY) },
                    onOpenSettings = ::openSettings,
                    onFullscreenStateChanged = onPlaybackFullscreenChanged,
                )

                MobileSection.SETTINGS -> SettingsScreen(
                    runtime = runtime,
                    summaries = summaries,
                    syncState = syncState,
                    activeSourceName = activeSummary?.name,
                    hasActivePlayback =
                        activeSelection != null ||
                            vodFullscreen ||
                            seriesFullscreen,
                    onOpenLive = { openSection(MobileSection.LIVE) },
                    onOpenSourceInLive = { sourceId ->
                        if (sourceId != activeSourceId && activeSelection != null) {
                            stopLivePresentation {
                                runtime.livePlaybackPresentationSession.clear()
                            }
                        }
                        rememberActiveSource(sourceId)
                        runtime.onDemandPresentationSession.clear()
                        section = MobileSection.LIVE
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

            val resolvedOrigin = playbackOrigin
            if (
                resolvedOrigin != null &&
                (vodFullscreen || seriesFullscreen)
            ) {
                PlaybackOriginBadge(origin = resolvedOrigin)
            }
        }
    }
}

@Composable
private fun MobileAppHeader(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = "OWNPLAY",
                modifier = Modifier.align(Alignment.CenterStart),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                )
            }
        }
    }
}

@Composable
private fun MobilePrimaryNavigationBar(
    homeSelected: Boolean,
    liveSelected: Boolean,
    librarySelected: Boolean,
    downloadsSelected: Boolean,
    onOpenHome: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = Color.Transparent,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        NavigationBar(
            modifier = Modifier.height(64.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = homeSelected,
                onClick = onOpenHome,
                icon = {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = "Home",
                        modifier = Modifier.size(23.dp),
                    )
                },
                label = { Text("Home", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = colors,
            )
            NavigationBarItem(
                selected = liveSelected,
                onClick = onOpenLive,
                icon = {
                    Icon(
                        Icons.Filled.LiveTv,
                        contentDescription = "Live",
                        modifier = Modifier.size(23.dp),
                    )
                },
                label = { Text("Live", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = colors,
            )
            NavigationBarItem(
                selected = librarySelected,
                onClick = onOpenLibrary,
                icon = {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = "Library",
                        modifier = Modifier.size(23.dp),
                    )
                },
                label = { Text("Library", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = colors,
            )
            NavigationBarItem(
                selected = downloadsSelected,
                onClick = onOpenDownloads,
                icon = {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Downloads",
                        modifier = Modifier.size(23.dp),
                    )
                },
                label = { Text("Downloads", style = MaterialTheme.typography.labelMedium) },
                alwaysShowLabel = true,
                colors = colors,
            )
        }
    }
}

@Composable
private fun MobileNoSourceScreen(
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
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
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
                    TextButton(
                        onClick = onAddPlaylist,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("Open Settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileConfigurationBoundary(content: @Composable () -> Unit) {
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
