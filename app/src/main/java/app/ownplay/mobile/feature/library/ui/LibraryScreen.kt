package app.ownplay.mobile.feature.library.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.downloads.domain.DownloadDetailsNavigation
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.design.OwnPlayFeaturePlaceholder
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.domain.LibraryCatalogSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingItem
import app.ownplay.mobile.feature.library.domain.LibraryDetailRefreshResult
import app.ownplay.mobile.feature.library.domain.LibraryDetailStartPolicy
import app.ownplay.mobile.feature.library.domain.LibraryEpisodeAutoplayPolicy
import app.ownplay.mobile.feature.library.domain.LibraryMovieSummary
import app.ownplay.mobile.feature.library.domain.LibraryRepository
import app.ownplay.mobile.feature.library.domain.LibrarySearchPolicy
import app.ownplay.mobile.feature.library.domain.LibrarySeriesDetail
import app.ownplay.mobile.feature.library.domain.LibrarySeriesDetailMetadata
import app.ownplay.mobile.feature.library.domain.LibrarySeriesMetadataLoadResult
import app.ownplay.mobile.feature.library.domain.LibrarySeriesSummary
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackPresentation
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionState
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.ui.FullscreenPlaybackWindow
import app.ownplay.mobile.feature.playback.ui.PlaybackTrackControlsOverlay
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoContentMode
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoSurface
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    openDownloadDetails: DownloadDetailsNavigation? = null,
    onDownloadDetailsNavigationConsumed: () -> Unit = {},
    onReturnFromDownloadDetails: (() -> Unit)? = null,
    onOpenDownloads: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val playbackScope = rememberCoroutineScope()
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val activeSource by activeSourceFlow.collectAsState(initial = null)
    val playbackState by services.playbackSessionController.state.collectAsState()
    val displayPreferences by services.displayPreferencesRepository.preferences.collectAsState(
        initial = DisplayPreferences(),
    )

    val source = activeSource
    if (source == null) {
        OwnPlayFeaturePlaceholder(
            title = "Library",
            message = "Add or select a source in Settings to browse Movies and Series.",
            modifier = modifier,
        )
        return
    }

    var externalOpenTarget by remember(source.sourceId) {
        mutableStateOf<LibraryExternalOpenTarget?>(null)
    }
    LaunchedEffect(openDownloadDetails, source.sourceId) {
        val request = openDownloadDetails ?: return@LaunchedEffect
        if (request.sourceId != source.sourceId) return@LaunchedEffect
        val item = services.downloadRepository.get(request.downloadId)
        if (
            item == null ||
            item.sourceId != request.sourceId ||
            item.mediaKind != request.mediaKind ||
            item.contentId != request.contentId
        ) {
            onDownloadDetailsNavigationConsumed()
            onReturnFromDownloadDetails?.invoke()
            return@LaunchedEffect
        }
        externalOpenTarget = when (request.mediaKind) {
            DownloadMediaKind.MOVIE -> LibraryExternalOpenTarget.Movie(
                sourceId = request.sourceId,
                movieId = request.contentId,
            )
            DownloadMediaKind.EPISODE -> {
                val context = services.downloadPresentationMetadataResolver
                    .resolveEpisodeContext(item)
                if (context == null) {
                    onDownloadDetailsNavigationConsumed()
                    onReturnFromDownloadDetails?.invoke()
                    return@LaunchedEffect
                }
                LibraryExternalOpenTarget.Episode(
                    sourceId = request.sourceId,
                    seriesId = context.seriesId,
                    episodeId = request.contentId,
                )
            }
        }
    }

    var initialRefreshError by remember(source.sourceId) { mutableStateOf<String?>(null) }
    var initialRefreshInProgress by remember(source.sourceId) { mutableStateOf(false) }
    LaunchedEffect(source.sourceId, source.lastSuccessfulRefreshAtEpochMs) {
        if (source.enabled && source.lastSuccessfulRefreshAtEpochMs == null) {
            initialRefreshError = null
            initialRefreshInProgress = true
            try {
                val result = services.sourceRepository.refreshSource(source.sourceId)
                if (result is app.ownplay.mobile.sources.domain.SourceRefreshResult.Failure) {
                    initialRefreshError = result.safeMessage ?: "Source refresh failed."
                }
            } finally {
                initialRefreshInProgress = false
            }
        }
    }

    LibrarySourceScreen(
        source = source,
        repository = services.libraryRepository,
        downloadRepository = services.downloadRepository,
        artworkLoader = services.libraryArtworkLoader,
        playbackSessionController = services.playbackSessionController,
        externalOpenTarget = externalOpenTarget,
        onExternalOpenConsumed = {
            externalOpenTarget = null
            onDownloadDetailsNavigationConsumed()
        },
        onReturnFromExternalDetail = onReturnFromDownloadDetails,
        onOpenDownloads = onOpenDownloads,
        compactMediaRows = displayPreferences.compactMediaRows,
        showCategoryFlags = displayPreferences.showCategoryFlags,
        hideCategoryPrefix = displayPreferences.hideLibraryCategoryPrefix,
        catalogLoadError = initialRefreshError,
        catalogLoading = initialRefreshInProgress,
        modifier = modifier,
    )

    val libraryTarget = playbackState.target as? PlaybackTarget.Library
    val libraryMediaKey = libraryTarget?.let(::libraryPlaybackMediaKey)
    var cancelledAutoplayKey by remember(source.sourceId) { mutableStateOf<String?>(null) }
    var playbackSeriesDetail by remember(source.sourceId) {
        mutableStateOf<LibrarySeriesDetail?>(null)
    }
    var playbackSeriesDetailResolved by remember(source.sourceId) { mutableStateOf(false) }
    LaunchedEffect(libraryTarget, source.sourceId) {
        playbackSeriesDetail = null
        playbackSeriesDetailResolved = false
        val episodeTarget = libraryTarget as? PlaybackTarget.Episode
        val seriesId = episodeTarget?.seriesId
        if (seriesId == null) {
            playbackSeriesDetailResolved = true
            return@LaunchedEffect
        }
        services.libraryRepository.observeSeriesDetail(source.sourceId, seriesId).collect { detail ->
            playbackSeriesDetail = detail
            playbackSeriesDetailResolved = true
        }
    }
    val autoplayCancelled =
        libraryMediaKey != null && cancelledAutoplayKey == libraryMediaKey

    LaunchedEffect(
        playbackState.endedNaturally,
        libraryTarget,
        playbackSeriesDetail,
        playbackSeriesDetailResolved,
        cancelledAutoplayKey,
    ) {
        if (!playbackState.endedNaturally || libraryTarget == null) return@LaunchedEffect
        when (libraryTarget) {
            is PlaybackTarget.Movie -> services.playbackSessionController.clear()
            is PlaybackTarget.Episode -> {
                if (libraryTarget.seriesId != null && !playbackSeriesDetailResolved) {
                    return@LaunchedEffect
                }
                val nextEpisode =
                    if (
                        libraryTarget.seriesId != null &&
                        playbackSeriesDetail?.series?.seriesId == libraryTarget.seriesId
                    ) {
                        LibraryEpisodeAutoplayPolicy.nextEpisode(
                            requireNotNull(playbackSeriesDetail),
                            libraryTarget.episodeId,
                        )
                    } else {
                        null
                    }
                if (!autoplayCancelled && nextEpisode != null) {
                    services.playbackSessionController.activateLibraryMedia(
                        PlaybackTarget.Episode(
                            sourceId = libraryTarget.sourceId,
                            episodeId = nextEpisode.episodeId,
                            seriesId = libraryTarget.seriesId,
                        ),
                    )
                } else {
                    services.playbackSessionController.clear()
                }
            }
        }
    }

    if (
        libraryTarget != null &&
        libraryTarget.sourceId == source.sourceId &&
        playbackState.presentation == PlaybackPresentation.FULLSCREEN
    ) {
        LibraryPlaybackFullscreenPresentation(
            target = libraryTarget,
            seriesDetail = playbackSeriesDetail,
            autoplayCancelled = autoplayCancelled,
            playbackState = playbackState,
            playbackSessionController = services.playbackSessionController,
            playbackEngine = services.playbackEngine,
            onPictureInPicture = {
                context.findMainActivity()?.requestOwnPlayPictureInPicture()
            },
            onRetry = {
                playbackScope.launch {
                    services.playbackSessionController.retryActiveTarget()
                }
            },
            onAutoplayCancel = {
                cancelledAutoplayKey = libraryMediaKey
            },
            onDismiss = services.playbackSessionController::clear,
        )
    }

}

@Composable
private fun LibrarySourceScreen(
    source: SourceSummary,
    repository: LibraryRepository,
    downloadRepository: DownloadRepository,
    artworkLoader: LibraryArtworkLoader,
    playbackSessionController: PlaybackSessionController,
    externalOpenTarget: LibraryExternalOpenTarget?,
    onExternalOpenConsumed: () -> Unit,
    onReturnFromExternalDetail: (() -> Unit)?,
    onOpenDownloads: () -> Unit,
    compactMediaRows: Boolean,
    showCategoryFlags: Boolean,
    hideCategoryPrefix: Boolean,
    catalogLoadError: String?,
    catalogLoading: Boolean,
    modifier: Modifier,
) {
    var selectedMovieId by rememberSaveable(source.sourceId.value) { mutableStateOf<String?>(null) }
    var selectedSeriesId by rememberSaveable(source.sourceId.value) { mutableStateOf<String?>(null) }
    var selectedEpisodeId by rememberSaveable(source.sourceId.value) { mutableStateOf<String?>(null) }
    var externalDetailReturnActive by rememberSaveable(source.sourceId.value) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(source.sourceId.value) { mutableStateOf("") }
    val catalogStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(externalOpenTarget, source.sourceId) {
        val target = externalOpenTarget ?: return@LaunchedEffect
        when (target) {
            is LibraryExternalOpenTarget.Movie -> {
                if (target.sourceId != source.sourceId) return@LaunchedEffect
                externalDetailReturnActive = onReturnFromExternalDetail != null
                selectedSeriesId = null
                selectedEpisodeId = null
                selectedMovieId = target.movieId
            }
            is LibraryExternalOpenTarget.Episode -> {
                if (target.sourceId != source.sourceId) return@LaunchedEffect
                externalDetailReturnActive = onReturnFromExternalDetail != null
                selectedMovieId = null
                selectedSeriesId = target.seriesId
                selectedEpisodeId = target.episodeId
            }
        }
        onExternalOpenConsumed()
    }

    fun returnFromExternalDetailIfNeeded() {
        if (!externalDetailReturnActive) return
        externalDetailReturnActive = false
        onReturnFromExternalDetail?.invoke()
    }

    fun closeMovieDetail() {
        selectedMovieId = null
        returnFromExternalDetailIfNeeded()
    }

    fun closeSeriesDetail() {
        selectedSeriesId = null
        selectedEpisodeId = null
        returnFromExternalDetailIfNeeded()
    }

    BackHandler(enabled = selectedMovieId != null) {
        closeMovieDetail()
    }

    val openMovieId = selectedMovieId
    if (openMovieId != null) {
        LibraryMovieDetailScreen(
            source = source,
            movieId = openMovieId,
            repository = repository,
            downloadRepository = downloadRepository,
            artworkLoader = artworkLoader,
            playbackSessionController = playbackSessionController,
            showProviderFlags = showCategoryFlags,
            hideProviderPrefix = hideCategoryPrefix,
            onBack = ::closeMovieDetail,
            modifier = modifier,
        )
        return
    }

    val openSeriesId = selectedSeriesId
    if (openSeriesId != null) {
        LibrarySeriesDetailScreen(
            source = source,
            seriesId = openSeriesId,
            repository = repository,
            downloadRepository = downloadRepository,
            artworkLoader = artworkLoader,
            playbackSessionController = playbackSessionController,
            showProviderFlags = showCategoryFlags,
            hideProviderPrefix = hideCategoryPrefix,
            initialEpisodeId = selectedEpisodeId,
            onBack = ::closeSeriesDetail,
            modifier = modifier,
        )
        return
    }

    val catalogFlow = remember(repository, source.sourceId) {
        repository.observeCatalog(source.sourceId)
    }
    val catalog by catalogFlow.collectAsState(
        initial = LibraryCatalogSnapshot(
            movieCategories = emptyList(),
            seriesCategories = emptyList(),
            movies = emptyList(),
            series = emptyList(),
        ),
    )
    val scope = rememberCoroutineScope()

    catalogStateHolder.SaveableStateProvider(
        key = "library-catalog:" + source.sourceId.value,
    ) {
        LibraryCatalogContent(
        source = source,
        catalog = catalog,
        artworkLoader = artworkLoader,
        compactMediaRows = compactMediaRows,
        showCategoryFlags = showCategoryFlags,
        hideCategoryPrefix = hideCategoryPrefix,
        catalogLoadError = catalogLoadError,
        catalogLoading = catalogLoading,
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        onOpenMovie = { movieId ->
            externalDetailReturnActive = false
            selectedMovieId = movieId
        },
        onOpenSeries = { seriesId ->
            externalDetailReturnActive = false
            selectedSeriesId = seriesId
            selectedEpisodeId = null
        },
        onOpenContinueWatching = { item ->
            externalDetailReturnActive = false
            when (item.contentKind) {
                LibraryContentKind.MOVIE -> selectedMovieId = item.contentId
                LibraryContentKind.EPISODE -> {
                    selectedSeriesId = item.seriesId
                    selectedEpisodeId = item.contentId
                }
                LibraryContentKind.SERIES -> Unit
            }
        },
        onToggleFavorite = { contentKind, contentId, favorite ->
            scope.launch {
                repository.setFavorite(
                    sourceId = source.sourceId,
                    contentKind = contentKind,
                    contentId = contentId,
                    favorite = favorite,
                )
            }
        },
        showDownloads = true,
        onOpenDownloads = onOpenDownloads,
        onRemoveContinueWatching = { item ->
            scope.launch {
                repository.removeFromContinueWatching(
                    sourceId = source.sourceId,
                    contentKind = item.contentKind,
                    contentId = item.contentId,
                )
            }
        },
        modifier = modifier,
        )
    }
}

@Composable
private fun LibrarySectionTitle(title: String) {
    Text(
        text = title,
        color = OwnPlayColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LibraryContinueWatchingRow(
    item: LibraryContinueWatchingItem,
    compact: Boolean,
    onOpenDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    var showActions by remember(item.contentKind, item.contentId) { mutableStateOf(false) }
    val context = when (item.contentKind) {
        LibraryContentKind.MOVIE -> "Movie"
        LibraryContentKind.EPISODE -> listOfNotNull(
            item.seriesTitle,
            item.seasonNumber?.let { season ->
                item.episodeNumber?.let { episode -> "S$season • E$episode" }
            },
        ).joinToString(" • ").ifBlank { "Episode" }
        LibraryContentKind.SERIES -> "Series"
    }
    val progress = (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)

    Surface(
        color = OwnPlayColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpenDetails,
                onLongClick = { showActions = true },
            ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = context, color = OwnPlayColors.TextSecondary)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("Continue Watching") },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showActions = false
                        onOpenDetails()
                    },
                ) { Text("Open details") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showActions = false
                        onRemove()
                    },
                ) { Text("Remove from Continue Watching") }
            },
        )
    }
}

@Composable
private fun LibrarySeriesDetailScreen(
    source: SourceSummary,
    seriesId: String,
    repository: LibraryRepository,
    downloadRepository: DownloadRepository,
    artworkLoader: LibraryArtworkLoader,
    playbackSessionController: PlaybackSessionController,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    initialEpisodeId: String? = null,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val detailFlow = remember(repository, source.sourceId, seriesId) {
        repository.observeSeriesDetail(source.sourceId, seriesId)
    }
    val detail by detailFlow.collectAsState(initial = null)
    val activePlaybackState by playbackSessionController.state.collectAsState()
    val downloadsFlow = remember(downloadRepository, source.sourceId) {
        downloadRepository.observeDownloads(source.sourceId)
    }
    val downloads by downloadsFlow.collectAsState(initial = emptyList())
    val catalogFlow = remember(repository, source.sourceId) {
        repository.observeCatalog(source.sourceId)
    }
    val catalog by catalogFlow.collectAsState(
        initial = LibraryCatalogSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
    )
    val episodeDownloads = remember(downloads) {
        downloads.filter { it.mediaKind == DownloadMediaKind.EPISODE }.associateBy { it.contentId }
    }
    var refreshing by remember(source.sourceId, seriesId) { mutableStateOf(true) }
    var refreshResult by remember(source.sourceId, seriesId) {
        mutableStateOf<LibraryDetailRefreshResult?>(null)
    }
    var seriesMetadata by remember(source.sourceId, seriesId) {
        mutableStateOf<LibrarySeriesDetailMetadata?>(null)
    }
    var pageName by rememberSaveable(source.sourceId.value, seriesId) {
        mutableStateOf(LibrarySeriesDetailPage.DETAIL.name)
    }
    var selectedSeasonNumber by rememberSaveable(source.sourceId.value, seriesId) {
        mutableStateOf<Int?>(null)
    }
    var selectedEpisodeId by rememberSaveable(source.sourceId.value, seriesId, initialEpisodeId) {
        mutableStateOf(initialEpisodeId)
    }
    val scope = rememberCoroutineScope()
    val page = LibrarySeriesDetailPage.entries.firstOrNull { it.name == pageName }
        ?: LibrarySeriesDetailPage.DETAIL

    LaunchedEffect(repository, source.sourceId, seriesId) {
        refreshing = true
        val result = repository.loadSeriesMetadata(source.sourceId, seriesId)
        seriesMetadata = (result as? LibrarySeriesMetadataLoadResult.Loaded)?.metadata
        refreshResult = result.toRefreshResult()
        refreshing = false
    }

    LaunchedEffect(detail, initialEpisodeId) {
        val episodeId = initialEpisodeId ?: return@LaunchedEffect
        val current = detail ?: return@LaunchedEffect
        current.seasons.firstOrNull { season ->
            season.episodes.any { episode -> episode.episodeId == episodeId }
        }?.let { season ->
            selectedSeasonNumber = season.seasonNumber
            selectedEpisodeId = episodeId
            pageName = LibrarySeriesDetailPage.EPISODE_DETAIL.name
        }
    }

    LaunchedEffect(activePlaybackState.target, seriesId) {
        val episodeTarget = activePlaybackState.target as? PlaybackTarget.Episode
        if (episodeTarget?.seriesId != seriesId) return@LaunchedEffect
        selectedEpisodeId = episodeTarget.episodeId
        detail?.seasons?.firstOrNull { season ->
            season.episodes.any { episode -> episode.episodeId == episodeTarget.episodeId }
        }?.let { season ->
            selectedSeasonNumber = season.seasonNumber
        }
    }

    val currentDetail = detail
    val seriesDisplayTitle = currentDetail?.series?.title?.let { rawTitle ->
        ProviderCategoryDisplayPolicy.label(
            rawName = rawTitle,
            hideRegionPrefix = hideProviderPrefix,
            showFlag = showProviderFlags,
        )
    }
    val orderedSeasons = remember(currentDetail) {
        currentDetail?.seasons.orEmpty().sortedBy { it.seasonNumber }
    }
    val selectedSeason = orderedSeasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
    val orderedEpisodes = remember(selectedSeason) {
        selectedSeason?.episodes
            .orEmpty()
            .sortedWith(compareBy({ it.episodeNumber }, { it.episodeId }))
    }
    val selectedEpisode = orderedEpisodes.firstOrNull { it.episodeId == selectedEpisodeId }
    val backLabel = when (page) {
        LibrarySeriesDetailPage.DETAIL -> "Back"
        LibrarySeriesDetailPage.SEASONS -> "Back to Series"
        LibrarySeriesDetailPage.EPISODES -> "Back to Seasons"
        LibrarySeriesDetailPage.EPISODE_DETAIL -> "Back to Episodes"
    }

    fun navigateBack() {
        when (page) {
            LibrarySeriesDetailPage.EPISODE_DETAIL -> {
                selectedEpisodeId = null
                pageName = LibrarySeriesDetailPage.EPISODES.name
            }
            LibrarySeriesDetailPage.EPISODES -> {
                selectedEpisodeId = null
                pageName = LibrarySeriesDetailPage.SEASONS.name
            }
            LibrarySeriesDetailPage.SEASONS -> {
                selectedSeasonNumber = null
                pageName = LibrarySeriesDetailPage.DETAIL.name
            }
            LibrarySeriesDetailPage.DETAIL -> onBack()
        }
    }

    BackHandler(onBack = ::navigateBack)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::navigateBack) {
                        Text(backLabel)
                    }
                    if (page == LibrarySeriesDetailPage.DETAIL) {
                        TextButton(
                            enabled = !refreshing,
                            onClick = {
                                refreshing = true
                                scope.launch {
                                    val result = repository.loadSeriesMetadata(source.sourceId, seriesId)
                                    seriesMetadata =
                                        (result as? LibrarySeriesMetadataLoadResult.Loaded)?.metadata
                                    refreshResult = result.toRefreshResult()
                                    refreshing = false
                                }
                            },
                        ) {
                            Text(if (refreshing) "Refreshing…" else "Refresh")
                        }
                    }
                }
                if (page != LibrarySeriesDetailPage.DETAIL) {
                    Text(
                        text = listOfNotNull(
                            detail?.series?.title,
                            when (page) {
                                LibrarySeriesDetailPage.SEASONS -> "Seasons"
                                LibrarySeriesDetailPage.EPISODES ->
                                    selectedSeasonNumber?.let { "Season ${it}" } ?: "Episodes"
                                LibrarySeriesDetailPage.EPISODE_DETAIL ->
                                    selectedEpisode?.let { "Episode ${it.episodeNumber}" } ?: "Episode"
                                LibrarySeriesDetailPage.DETAIL -> null
                            },
                        ).joinToString(" · "),
                        color = OwnPlayColors.TextMuted,
                    )
                }
            }
        }

        if (currentDetail == null) {
            item {
                Text(
                    text = when {
                        refreshing -> "Loading Series details…"
                        refreshResult == LibraryDetailRefreshResult.UNAVAILABLE ->
                            "This Series is no longer available."
                        refreshResult == LibraryDetailRefreshResult.UNSUPPORTED_SOURCE ->
                            "Episode details are not supported for this source."
                        else -> "Series details are not available."
                    },
                    color = OwnPlayColors.TextMuted,
                )
            }
            return@LazyColumn
        }

        val seriesArtwork = seriesMetadata?.posterUrl ?: currentDetail.series.posterUrl

        when (page) {
            LibrarySeriesDetailPage.DETAIL -> {
                item {
                    LibrarySeriesMetadata(
                        detail = currentDetail,
                        displayTitle = seriesDisplayTitle ?: currentDetail.series.title,
                        metadata = seriesMetadata,
                        artworkLoader = artworkLoader,
                        onFavorite = {
                            scope.launch {
                                repository.setFavorite(
                                    sourceId = source.sourceId,
                                    contentKind = LibraryContentKind.SERIES,
                                    contentId = currentDetail.series.seriesId,
                                    favorite = !currentDetail.series.favorite,
                                )
                            }
                        },
                    )
                }

                item {
                    Surface(
                        color = OwnPlayColors.Surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSeasonNumber = orderedSeasons
                                    .firstOrNull { season ->
                                        season.episodes.any { episode ->
                                            episode.episodeId == selectedEpisodeId
                                        }
                                    }
                                    ?.seasonNumber
                                    ?: orderedSeasons.firstOrNull()?.seasonNumber
                                pageName = LibrarySeriesDetailPage.SEASONS.name
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = "Seasons",
                                    color = OwnPlayColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = orderedSeasons.size.toString() + " available",
                                    color = OwnPlayColors.TextSecondary,
                                )
                            }
                            Text("›", color = OwnPlayColors.TextMuted)
                        }
                    }
                }

                when (refreshResult) {
                    LibraryDetailRefreshResult.FAILED -> item {
                        Text(
                            text = "Could not refresh episode details. Showing cached details when available.",
                            color = OwnPlayColors.TextMuted,
                        )
                    }
                    LibraryDetailRefreshResult.UNSUPPORTED_SOURCE -> item {
                        Text(
                            text = "Episode details are not supported for this source.",
                            color = OwnPlayColors.TextMuted,
                        )
                    }
                    else -> Unit
                }

                if (orderedSeasons.isEmpty()) {
                    item {
                        Text(
                            text = if (refreshing) {
                                "Refreshing episode details…"
                            } else {
                                "No seasons are available for this Series."
                            },
                            color = OwnPlayColors.TextMuted,
                        )
                    }
                }
            }

            LibrarySeriesDetailPage.SEASONS -> {
                item {
                    Text(
                        text = seriesDisplayTitle ?: currentDetail.series.title,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (orderedSeasons.isEmpty()) {
                    item {
                        Text("No seasons are available for this Series.", color = OwnPlayColors.TextMuted)
                    }
                } else {
                    items(
                        items = orderedSeasons,
                        key = { season -> "season:${season.seasonNumber}" },
                    ) { season ->
                        Surface(
                            color = OwnPlayColors.Surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSeasonNumber = season.seasonNumber
                                    selectedEpisodeId = null
                                    pageName = LibrarySeriesDetailPage.EPISODES.name
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(modifier = Modifier.width(74.dp)) {
                                    LibraryArtwork(
                                        url = seriesArtwork,
                                        loader = artworkLoader,
                                        compact = true,
                                        expandPoster = true,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "Season ${season.seasonNumber}",
                                        color = OwnPlayColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = season.episodes.size.toString() + " episodes",
                                        color = OwnPlayColors.TextSecondary,
                                    )
                                }
                                Text("›", color = OwnPlayColors.TextMuted)
                            }
                        }
                    }
                }
            }

            LibrarySeriesDetailPage.EPISODES -> {
                item {
                    Text(
                        text = selectedSeason?.let { "Season ${it.seasonNumber}" } ?: "Episodes",
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (orderedEpisodes.isEmpty()) {
                    item {
                        Text(
                            text = "No episodes are available for this season.",
                            color = OwnPlayColors.TextMuted,
                        )
                    }
                } else {
                    items(
                        items = orderedEpisodes,
                        key = { episode -> "episode:${episode.episodeId}" },
                    ) { episode ->
                        Surface(
                            color = OwnPlayColors.Surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedEpisodeId = episode.episodeId
                                    pageName = LibrarySeriesDetailPage.EPISODE_DETAIL.name
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(modifier = Modifier.width(74.dp)) {
                                    LibraryArtwork(
                                        url = seriesArtwork,
                                        loader = artworkLoader,
                                        compact = true,
                                        expandPoster = true,
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = "Episode ${episode.episodeNumber}",
                                        color = OwnPlayColors.TextSecondary,
                                    )
                                    Text(
                                        text = episode.title,
                                        color = OwnPlayColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    LibraryMovieDetailPresentation.runtimeLabel(episode.durationMs)
                                        ?.let { duration ->
                                            Text(
                                                text = duration,
                                                color = OwnPlayColors.TextMuted,
                                            )
                                        }
                                }
                                Text("›", color = OwnPlayColors.TextMuted)
                            }
                        }
                    }
                }
            }

            LibrarySeriesDetailPage.EPISODE_DETAIL -> {
                if (selectedEpisode == null) {
                    item {
                        Text(
                            text = "This Episode is no longer available.",
                            color = OwnPlayColors.TextMuted,
                        )
                    }
                } else {
                    val episode = selectedEpisode
                    val resumeAvailable = catalog.continueWatching.any { item ->
                        item.contentKind == LibraryContentKind.EPISODE &&
                            item.contentId == episode.episodeId
                    }
                    item {
                        LibraryDetailHero(
                            title = episode.title,
                            artworkUrl = seriesArtwork,
                            artworkLoader = artworkLoader,
                            metadataLine = listOfNotNull(
                                selectedSeason?.seasonNumber?.let { "Season $it" },
                                "Episode ${episode.episodeNumber}",
                                LibraryMovieDetailPresentation.runtimeLabel(episode.durationMs),
                            ).joinToString(" • "),
                            description = null,
