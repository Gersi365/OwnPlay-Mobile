package app.ownplay.player.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.download.OfflineDownloadSpec
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.source.SourceResult
import app.ownplay.player.ui.OfflineMediaTvFocusPolicy
import app.ownplay.player.ui.view.ContentViewMode
import app.ownplay.player.ui.view.ContentViewModeMenu
import app.ownplay.player.ui.view.ContentViewModeStore
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val MISSING_FILE_REASON = "Downloaded file is missing"

private enum class UnifiedLibraryFilter {
    MOVIES,
    SERIES,
    ALL,
}

@Composable
internal fun UnifiedLibraryRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String?,
    sourceKind: String?,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val scope = rememberCoroutineScope()
    val downloadRuntime = remember(context) {
        OfflineDownloadFeatureRuntime(context.applicationContext)
    }
    val vodRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
    val seriesRuntime = remember(context) { SeriesFeatureRuntime(context.applicationContext) }
    val viewModeStore = remember(context) {
        ContentViewModeStore(context.applicationContext)
    }
    val libraryListState = rememberLazyListState()
    val libraryGridState = rememberLazyGridState()
    val libraryItemFocusRequester = remember { FocusRequester() }

    DisposableEffect(downloadRuntime, vodRuntime, seriesRuntime) {
        onDispose {
            downloadRuntime.close()
            vodRuntime.close()
            seriesRuntime.close()
        }
    }

    val downloads by downloadRuntime.observeAll().collectAsState(initial = emptyList())
    val presentationDownloads = if (isTelevision) emptyList() else downloads
    val libraryViewMode by viewModeStore.libraryMode.collectAsState(initial = ContentViewMode.CARDS)
    val vodFlow = remember(sourceId, vodRuntime) {
        sourceId?.let(vodRuntime::observeCatalog) ?: flowOf(VodCatalog())
    }
    val seriesFlow = remember(sourceId, seriesRuntime) {
        sourceId?.let(seriesRuntime::observeCatalog) ?: flowOf(SeriesCatalog())
    }
    val vodCatalog by vodFlow.collectAsState(initial = VodCatalog())
    val seriesCatalog by seriesFlow.collectAsState(initial = SeriesCatalog())

    var filter by remember(isTelevision) {
        mutableStateOf(UnifiedLibraryFilter.MOVIES)
    }
    var movieCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var seriesCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var offlineOnly by remember(isTelevision) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember(isTelevision) { mutableStateOf(isTelevision) }
    val refreshDemand = remember(sourceId, sourceKind) { LibraryCatalogRefreshDemand() }
    var movieRefreshDemanded by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var seriesRefreshDemanded by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var vodRefreshing by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var seriesRefreshing by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var vodRefreshWarning by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var seriesRefreshWarning by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var initialCatalogRefreshPending by remember(sourceId, sourceKind) {
        mutableStateOf(sourceId != null && sourceKind == SourceKinds.XTREAM)
    }
    val refreshing = when (filter) {
        UnifiedLibraryFilter.MOVIES -> vodRefreshing
        UnifiedLibraryFilter.SERIES -> seriesRefreshing
        UnifiedLibraryFilter.ALL -> false
    }
    val refreshWarning = when (filter) {
        UnifiedLibraryFilter.MOVIES -> vodRefreshWarning
        UnifiedLibraryFilter.SERIES -> seriesRefreshWarning
        UnifiedLibraryFilter.ALL -> false
    }
    val playbackSession by LibraryPlaybackPresentationSession.state.collectAsState()
    var playbackError by remember { mutableStateOf<String?>(null) }
    var selectedSeriesKey by remember { mutableStateOf<LibrarySeriesKey?>(null) }
    var focusItemKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var focusRequestGeneration by remember(sourceId) { mutableIntStateOf(0) }
    var rememberedFocusItemKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var initialLibraryItemFocusRequested by remember(sourceId) { mutableStateOf(false) }
    var pendingMovieReturnFocusKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var seriesReturnEpisodeId by remember(sourceId) { mutableStateOf<String?>(null) }
    var seriesReturnFocusGeneration by remember(sourceId) { mutableIntStateOf(0) }

    LaunchedEffect(isTelevision) {
        if (isTelevision) {
            offlineOnly = false
            searchExpanded = true
            if (filter == UnifiedLibraryFilter.ALL) {
                filter = UnifiedLibraryFilter.MOVIES
            }
        }
    }

    LaunchedEffect(vodCatalog.categories, movieCategoryKey) {
        val categories = vodCatalog.categories
        movieCategoryKey = when {
            categories.isEmpty() -> null
            movieCategoryKey != null && categories.any { it.providerCategoryKey == movieCategoryKey } -> movieCategoryKey
            else -> categories.first().providerCategoryKey
        }
    }

    LaunchedEffect(seriesCatalog.categories, seriesCategoryKey) {
        val categories = seriesCatalog.categories
        seriesCategoryKey = when {
            categories.isEmpty() -> null
            seriesCategoryKey != null && categories.any { it.providerCategoryKey == seriesCategoryKey } -> seriesCategoryKey
            else -> categories.first().providerCategoryKey
        }
    }

    LaunchedEffect(sourceId, sourceKind, filter, refreshDemand) {
        if (sourceId == null || sourceKind != SourceKinds.XTREAM) {
            initialCatalogRefreshPending = false
            return@LaunchedEffect
        }
        when (filter) {
            UnifiedLibraryFilter.MOVIES -> {
                if (refreshDemand.claim(LibraryCatalogRefreshTarget.MOVIES)) {
                    movieRefreshDemanded = true
                }
            }
            UnifiedLibraryFilter.SERIES -> {
                if (refreshDemand.claim(LibraryCatalogRefreshTarget.SERIES)) {
                    seriesRefreshDemanded = true
                }
            }
            UnifiedLibraryFilter.ALL -> initialCatalogRefreshPending = false
        }
    }

    LaunchedEffect(sourceId, sourceKind, movieRefreshDemanded) {
        val resolvedSourceId = sourceId
        if (
            !movieRefreshDemanded ||
            resolvedSourceId == null ||
            sourceKind != SourceKinds.XTREAM
        ) {
            return@LaunchedEffect
        }
        vodRefreshing = true
        vodRefreshWarning = false
        initialCatalogRefreshPending = false
        try {
            vodRefreshWarning = vodRuntime.refresh(resolvedSourceId) is SourceResult.Failure
        } finally {
            vodRefreshing = false
        }
    }

    LaunchedEffect(sourceId, sourceKind, seriesRefreshDemanded) {
        val resolvedSourceId = sourceId
        if (
            !seriesRefreshDemanded ||
            resolvedSourceId == null ||
            sourceKind != SourceKinds.XTREAM
        ) {
            return@LaunchedEffect
        }
        seriesRefreshing = true
        seriesRefreshWarning = false
        initialCatalogRefreshPending = false
        try {
            seriesRefreshWarning = seriesRuntime.refresh(resolvedSourceId) is SourceResult.Failure
        } finally {
            seriesRefreshing = false
        }
    }

    val seriesGroups = remember(presentationDownloads) { groupLibrarySeries(presentationDownloads) }
    val selectedSeriesGroup = selectedSeriesKey?.let { key ->
        seriesGroups.firstOrNull { it.key == key }
    }

    LaunchedEffect(selectedSeriesKey, seriesGroups) {
        if (selectedSeriesKey != null && selectedSeriesGroup == null) {
            selectedSeriesKey = null
        }
    }

    val seriesBackOwner = remember { Any() }
    DisposableEffect(selectedSeriesKey, seriesBackOwner) {
        if (selectedSeriesKey != null) {
            PlaybackInteractionBridge.registerBackAction(seriesBackOwner) {
                selectedSeriesKey = null
            }
        }
        onDispose { PlaybackInteractionBridge.clearBackAction(seriesBackOwner) }
    }

    fun playDownload(download: OfflineDownload) {
        scope.launch {
            val request = downloadRuntime.playbackRequest(download.downloadId)
            if (request == null) {
                playbackError = "The offline file is unavailable. Download it again to restore offline playback."
                return@launch
            }
            val progress = downloadRuntime.playbackProgress(download.downloadId)
            playbackError = null
            runtime.playbackController.start(request)
            LibraryPlaybackPresentationSession.show(
                LibraryPlaybackSession(
                    download = download,
                    initialPositionMs = progress
                        ?.takeIf { !it.completed }
                        ?.positionMs
                        ?.coerceAtLeast(0L)
                        ?: 0L,
                ),
            )
        }
    }

    playbackSession?.let { session ->
        LibraryPlaybackScreen(
            runtime = runtime,
            session = session,
            onExit = {
                runtime.playbackController.stop()
                LibraryPlaybackPresentationSession.clear()
                val movieReturnKey = pendingMovieReturnFocusKey
                pendingMovieReturnFocusKey = null
                if (selectedSeriesKey != null && seriesReturnEpisodeId != null) {
                    seriesReturnFocusGeneration += 1
                } else if (movieReturnKey != null) {
                    focusItemKey = movieReturnKey
                    rememberedFocusItemKey = movieReturnKey
                    focusRequestGeneration += 1
                }
            },
            onProgress = { positionMs, durationMs ->
                scope.launch {
                    downloadRuntime.savePlaybackProgress(
                        downloadId = session.download.downloadId,
                        positionMs = positionMs,
                        durationMs = durationMs,
                    )
                }
            },
            onFullscreenStateChanged = onFullscreenStateChanged,
        )
        return
    }

    if (selectedSeriesGroup != null) {
        LibrarySeriesDetailScreen(
            group = selectedSeriesGroup,
            playbackError = playbackError,
            returnFocusEpisodeId = seriesReturnEpisodeId,
            returnFocusGeneration = seriesReturnFocusGeneration,
            onBack = {
                selectedSeriesKey = null
                seriesReturnEpisodeId = null
                rememberedFocusItemKey?.let { target ->
                    focusItemKey = target
                    focusRequestGeneration += 1
                }
            },
            onOpenFullSeries = selectedSeriesGroup.seriesId?.let { seriesId ->
                {
                    playbackError = null
                    onOpenSeriesDetails(selectedSeriesGroup.key.sourceId, seriesId)
                }
            },
            onDownloadEpisode = { episode ->
                scope.launch {
                    enqueueSeriesEpisode(
                        downloadRuntime = downloadRuntime,
                        sourceId = selectedSeriesGroup.key.sourceId,
                        episode = episode,
                    )
                }
            },
            onPlay = { download ->
                pendingMovieReturnFocusKey = null
                seriesReturnEpisodeId = download.contentId
                playDownload(download)
            },
            onPause = { download -> scope.launch { downloadRuntime.pause(download.downloadId) } },
            onResume = { download -> scope.launch { downloadRuntime.resume(download.downloadId) } },
            onRetry = { download -> scope.launch { downloadRuntime.retry(download.downloadId) } },
            onRemove = { download -> scope.launch { downloadRuntime.remove(download.downloadId) } },
        )
        return
    }

    val normalizedQuery = query.trim().lowercase()
    val movieDownloadsByKey = remember(presentationDownloads) {
        presentationDownloads
            .filter { it.mediaKind == DownloadMediaKinds.MOVIE }
            .associateBy { "${it.sourceId}:${it.contentId}" }
    }
    val seriesGroupByIdentity = remember(seriesGroups) {
        seriesGroups.mapNotNull { group ->
            group.seriesId?.let { seriesId -> "${group.key.sourceId}:$seriesId" to group }
        }.toMap()
    }

    val visibleMovies = remember(
        vodCatalog.movies,
        sourceId,
        filter,
        movieCategoryKey,
        offlineOnly,
        normalizedQuery,
        movieDownloadsByKey,
    ) {
        if (sourceId == null) {
            emptyList()
        } else {
            vodCatalog.movies.filter { movie ->
                val download = movieDownloadsByKey["$sourceId:${movie.movieId}"]
                val offlineMatch = !offlineOnly || download?.countsForOfflineFilter() == true
                val categoryMatch =
                    filter != UnifiedLibraryFilter.MOVIES ||
                        movieCategoryKey == null ||
                        movie.categoryKey == movieCategoryKey
                val queryMatch = normalizedQuery.isBlank() || movie.name.lowercase().contains(normalizedQuery)
                offlineMatch && categoryMatch && queryMatch
            }
        }
    }
    val visibleSeries = remember(
        seriesCatalog.series,
        sourceId,
        filter,
        seriesCategoryKey,
        offlineOnly,
        normalizedQuery,
        seriesGroupByIdentity,
    ) {
        if (sourceId == null) {
            emptyList()
        } else {
            seriesCatalog.series.filter { series ->
                val group = seriesGroupByIdentity["$sourceId:${series.seriesId}"]
                val offlineMatch = !offlineOnly || group?.episodes?.any(OfflineDownload::countsForOfflineFilter) == true
                val categoryMatch =
                    filter != UnifiedLibraryFilter.SERIES ||
                        seriesCategoryKey == null ||
                        series.categoryKey == seriesCategoryKey
                val queryMatch = normalizedQuery.isBlank() || series.name.lowercase().contains(normalizedQuery)
                offlineMatch && categoryMatch && queryMatch
            }
        }
    }

    val catalogMovieKeys = remember(vodCatalog.movies, sourceId) {
        if (sourceId == null) emptySet() else vodCatalog.movies.map { "$sourceId:${it.movieId}" }.toSet()
    }
    val orphanedOfflineMovies = remember(
        presentationDownloads,
        catalogMovieKeys,
        sourceId,
        filter,
        movieCategoryKey,
        offlineOnly,
        normalizedQuery,
    ) {
        if (
            !offlineOnly ||
            sourceId == null ||
            (filter == UnifiedLibraryFilter.MOVIES && movieCategoryKey != null)
        ) {
            emptyList()
        } else {
            presentationDownloads.filter { download ->
                download.sourceId == sourceId &&
                    download.mediaKind == DownloadMediaKinds.MOVIE &&
                    download.countsForOfflineFilter() &&
                    "${download.sourceId}:${download.contentId}" !in catalogMovieKeys &&
                    (normalizedQuery.isBlank() || download.title.lowercase().contains(normalizedQuery))
            }
        }
    }
    val catalogSeriesKeys = remember(seriesCatalog.series, sourceId) {
        if (sourceId == null) emptySet() else seriesCatalog.series.map { "$sourceId:${it.seriesId}" }.toSet()
    }
    val orphanedOfflineSeries = remember(
        seriesGroups,
        catalogSeriesKeys,
        sourceId,
        filter,
        seriesCategoryKey,
        offlineOnly,
        normalizedQuery,
    ) {
        if (
            !offlineOnly ||
            sourceId == null ||
            (filter == UnifiedLibraryFilter.SERIES && seriesCategoryKey != null)
        ) {
            emptyList()
        } else {
            seriesGroups.filter { group ->
                val identity = group.seriesId?.let { "${group.key.sourceId}:$it" }
                group.key.sourceId == sourceId &&
                    group.episodes.any(OfflineDownload::countsForOfflineFilter) &&
                    (identity == null || identity !in catalogSeriesKeys) &&
                    (normalizedQuery.isBlank() || group.title.lowercase().contains(normalizedQuery))
            }
        }
    }

    val movieCount = if (filter == UnifiedLibraryFilter.SERIES) {
        0
    } else {
        visibleMovies.size + orphanedOfflineMovies.size
    }
    val seriesCount = if (filter == UnifiedLibraryFilter.MOVIES) {
        0
    } else {
        visibleSeries.size + orphanedOfflineSeries.size
    }
    val showMovieContinueWatching =
        filter == UnifiedLibraryFilter.MOVIES &&
            !offlineOnly &&
            normalizedQuery.isBlank() &&
            vodCatalog.continueWatching.isNotEmpty()
    val showSeriesContinueWatching =
        filter == UnifiedLibraryFilter.SERIES &&
            !offlineOnly &&
            normalizedQuery.isBlank() &&
            seriesCatalog.continueWatching.isNotEmpty()
    val hasItems =
        movieCount + seriesCount > 0 || showMovieContinueWatching || showSeriesContinueWatching
    val showInitialMobileLoading = shouldShowMobileLibraryInitialLoading(
        isTelevision = isTelevision,
        offlineOnly = offlineOnly,
        hasItems = hasItems,
        refreshing = refreshing,
        initialRefreshPending = initialCatalogRefreshPending,
    )
    val visibleFocusKeys = remember(
        filter,
        sourceId,
        visibleMovies,
        orphanedOfflineMovies,
        visibleSeries,
        orphanedOfflineSeries,
    ) {
        libraryVisibleFocusKeys(
            filter = filter,
            sourceId = sourceId,
            visibleMovies = visibleMovies,
            orphanedOfflineMovies = orphanedOfflineMovies,
            visibleSeries = visibleSeries,
            orphanedOfflineSeries = orphanedOfflineSeries,
        )
    }

    LaunchedEffect(isTelevision, visibleFocusKeys, libraryViewMode) {
        if (!isTelevision || visibleFocusKeys.isEmpty()) return@LaunchedEffect
        val currentTargetStillVisible = focusItemKey?.let(visibleFocusKeys::contains) == true
        if (initialLibraryItemFocusRequested && currentTargetStillVisible) return@LaunchedEffect
        val target = OfflineMediaTvFocusPolicy.preferredVisibleKey(
            visibleKeys = visibleFocusKeys,
            rememberedKey = rememberedFocusItemKey,
        ) ?: return@LaunchedEffect
        initialLibraryItemFocusRequested = true
        focusItemKey = target
        focusRequestGeneration += 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (isTelevision) 20.dp else 10.dp,
                vertical = if (isTelevision) 12.dp else 4.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (isTelevision) 10.dp else 4.dp),
    ) {
        if (isTelevision) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (offlineOnly) {
                            "Local files on this device · playback works without internet"
                        } else {
                            "Movies and Series from your active playlist"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                ContentViewModeMenu(
                    mode = libraryViewMode,
                    onModeSelected = { mode ->
                        scope.launch { viewModeStore.setLibraryMode(mode) }
                    },
                    prefix = "View",
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(UnifiedLibraryFilter.MOVIES, UnifiedLibraryFilter.SERIES).forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = {
                            filter = option
                            offlineOnly = false
                            query = ""
                        },
                        label = {
                            Text(
                                when (option) {
                                    UnifiedLibraryFilter.ALL -> "Offline"
                                    UnifiedLibraryFilter.MOVIES -> "Movies"
                                    UnifiedLibraryFilter.SERIES -> "Series"
                                },
                            )
                        },
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "library-search") {
                    IconButton(
                        onClick = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) query = ""
                        },
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchExpanded) {
                                "Close Library search"
                            } else {
                                "Search Library"
                            },
                        )
                    }
                }
                item(key = "library-view") {
                    ContentViewModeMenu(
                        mode = libraryViewMode,
                        onModeSelected = { mode ->
                            scope.launch { viewModeStore.setLibraryMode(mode) }
                        },
                    )
                }
                listItems(
                    items = UnifiedLibraryFilter.entries,
                    key = { it.name },
                ) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = {
                            filter = option
                            offlineOnly = option == UnifiedLibraryFilter.ALL
                            query = ""
                            searchExpanded = false
                        },
                        label = {
                            Text(
                                when (option) {
                                    UnifiedLibraryFilter.ALL -> "Offline"
                                    UnifiedLibraryFilter.MOVIES -> "Movies"
                                    UnifiedLibraryFilter.SERIES -> "Series"
                                },
                            )
                        },
                        leadingIcon = if (option == UnifiedLibraryFilter.ALL) {
                            {
                                Icon(
                                    Icons.Filled.DownloadDone,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
                if (refreshing && !showInitialMobileLoading) {
                    item(key = "library-refreshing") {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        when (filter) {
            UnifiedLibraryFilter.MOVIES -> LibraryCategoryStrip(
                label = "Movie categories",
                showLabel = isTelevision,
                selectedCategoryKey = movieCategoryKey,
                categories = vodCatalog.categories.map { it.providerCategoryKey to it.name },
                onCategorySelected = { movieCategoryKey = it },
            )
            UnifiedLibraryFilter.SERIES -> LibraryCategoryStrip(
                label = "Series categories",
                showLabel = isTelevision,
                selectedCategoryKey = seriesCategoryKey,
                categories = seriesCatalog.categories.map { it.providerCategoryKey to it.name },
                onCategorySelected = { seriesCategoryKey = it },
            )
            UnifiedLibraryFilter.ALL -> Unit
        }

        if (isTelevision || searchExpanded || query.isNotBlank()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                placeholder = {
                    Text(
                        when (filter) {
                            UnifiedLibraryFilter.ALL -> "Search Offline"
                            UnifiedLibraryFilter.MOVIES -> "Search Movies"
                            UnifiedLibraryFilter.SERIES -> "Search Series"
                        },
                    )
                },
                shape = RoundedCornerShape(10.dp),
            )
        }

        if (refreshWarning && !offlineOnly) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                    Text(
                        text = "This Library section could not refresh. Showing the saved catalog.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        playbackError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (showMovieContinueWatching && sourceId != null) {
            LibraryMovieContinueWatchingStrip(
                movies = vodCatalog.continueWatching,
                onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
            )
        }

        if (showSeriesContinueWatching && sourceId != null) {
            LibrarySeriesContinueWatchingStrip(
                episodes = seriesCatalog.continueWatching,
                onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
            )
        }

        if (showInitialMobileLoading) {
            LibraryLoadingState(modifier = Modifier.weight(1f))
            return
        }

        if (!hasItems) {
            LibraryEmptyState(
                offlineOnly = offlineOnly,
                sourceKind = sourceKind,
                modifier = Modifier.weight(1f),
            )
            return
        }

        LibraryCatalogView(
            viewMode = libraryViewMode,
            filter = filter,
            sourceId = sourceId,
            offlineOnly = offlineOnly,
            visibleMovies = visibleMovies,
            orphanedOfflineMovies = orphanedOfflineMovies,
            visibleSeries = visibleSeries,
            orphanedOfflineSeries = orphanedOfflineSeries,
            movieDownloadsByKey = movieDownloadsByKey,
            seriesGroupByIdentity = seriesGroupByIdentity,
            focusKeys = visibleFocusKeys,
            focusItemKey = focusItemKey,
            focusRequestGeneration = focusRequestGeneration,
            itemFocusRequester = libraryItemFocusRequester,
            listState = libraryListState,
            gridState = libraryGridState,
            onItemFocused = { itemKey -> rememberedFocusItemKey = itemKey },
            onOpenMovie = { movieSourceId, movieId ->
                onOpenMovieDetails(movieSourceId, movieId)
            },
            onOpenCatalogSeries = { seriesSourceId, seriesId, _ ->
                onOpenSeriesDetails(seriesSourceId, seriesId)
            },
            onOpenOfflineSeries = { group ->
                playbackError = null
                selectedSeriesKey = group.key
            },
            onPlayOfflineMovie = { download ->
                seriesReturnEpisodeId = null
                val catalogKey = libraryCatalogMovieFocusKey(
                    sourceId = download.sourceId,
                    movieId = download.contentId,
                )
                val offlineKey = libraryOfflineMovieFocusKey(download.downloadId)
                pendingMovieReturnFocusKey = when {
                    catalogKey in visibleFocusKeys -> catalogKey
                    offlineKey in visibleFocusKeys -> offlineKey
                    else -> rememberedFocusItemKey
                }
                playDownload(download)
            },
            onPauseMovie = { download -> scope.launch { downloadRuntime.pause(download.downloadId) } },
            onResumeMovie = { download -> scope.launch { downloadRuntime.resume(download.downloadId) } },
            onRetryMovie = { download -> scope.launch { downloadRuntime.retry(download.downloadId) } },
            onRemoveMovie = { download -> scope.launch { downloadRuntime.remove(download.downloadId) } },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibraryCategoryStrip(
    label: String,
    showLabel: Boolean,
    selectedCategoryKey: String?,
    categories: List<Pair<String, String>>,
    onCategorySelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (showLabel) 4.dp else 2.dp)) {
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            listItems(categories, key = { it.first }) { (categoryKey, categoryName) ->
                FilterChip(
                    selected = selectedCategoryKey == categoryKey,
                    onClick = { onCategorySelected(categoryKey) },
                    label = {
                        Text(
                            text = categoryName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryLoadingState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = "Loading Library",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Refreshing the selected catalog…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryEmptyState(
    offlineOnly: Boolean,
    sourceKind: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (offlineOnly) Icons.Filled.DownloadDone else Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (offlineOnly) "Nothing available offline" else "No matching media",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (offlineOnly) {
                    "Only completed downloads whose local files are still present appear here."
                } else if (sourceKind != SourceKinds.XTREAM) {
                    "Movies and Series require an Xtream-compatible source."
                } else {
                    "Try another category, Library filter or search term."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryCatalogView(
    viewMode: ContentViewMode,
    filter: UnifiedLibraryFilter,
    sourceId: String?,
    offlineOnly: Boolean,
    visibleMovies: List<VodMovie>,
    orphanedOfflineMovies: List<OfflineDownload>,
    visibleSeries: List<SeriesSummary>,
    orphanedOfflineSeries: List<LibrarySeriesGroup>,
    movieDownloadsByKey: Map<String, OfflineDownload>,
    seriesGroupByIdentity: Map<String, LibrarySeriesGroup>,
    focusKeys: List<String>,
    focusItemKey: String?,
    focusRequestGeneration: Int,
    itemFocusRequester: FocusRequester,
    listState: LazyListState,
    gridState: LazyGridState,
    onItemFocused: (String) -> Unit,
    onOpenMovie: (sourceId: String, movieId: String) -> Unit,
    onOpenCatalogSeries: (sourceId: String, seriesId: String, group: LibrarySeriesGroup?) -> Unit,
    onOpenOfflineSeries: (LibrarySeriesGroup) -> Unit,
    onPlayOfflineMovie: (OfflineDownload) -> Unit,
    onPauseMovie: (OfflineDownload) -> Unit,
    onResumeMovie: (OfflineDownload) -> Unit,
    onRetryMovie: (OfflineDownload) -> Unit,
    onRemoveMovie: (OfflineDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val cardMinSize = if (isTelevision) 172.dp else 150.dp
    val compactMinSize = if (isTelevision) 120.dp else 108.dp
    val focusIndex = remember(focusKeys, focusItemKey) { focusKeys.indexOf(focusItemKey) }

    LaunchedEffect(
        viewMode,
        focusItemKey,
        focusRequestGeneration,
        focusIndex,
    ) {
        if (focusRequestGeneration <= 0 || focusIndex < 0) return@LaunchedEffect
        when (viewMode) {
            ContentViewMode.LIST -> listState.scrollToItem(focusIndex)
            ContentViewMode.COMPACT,
            ContentViewMode.CARDS,
            -> gridState.scrollToItem(focusIndex)
        }
        withFrameNanos { }
        itemFocusRequester.requestFocus()
    }

    fun itemModifier(itemKey: String): Modifier {
        val requesterModifier = if (itemKey == focusItemKey) {
            Modifier.focusRequester(itemFocusRequester)
        } else {
            Modifier
        }
        return requesterModifier.onFocusChanged { focusState ->
            if (focusState.hasFocus) {
                onItemFocused(itemKey)
            }
        }
    }

    when (viewMode) {
        ContentViewMode.CARDS -> LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = cardMinSize),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (filter != UnifiedLibraryFilter.SERIES) {
                gridItems(visibleMovies, key = { "catalog-movie:${it.movieId}" }) { movie ->
                    val movieSourceId = sourceId ?: return@gridItems
                    UnifiedMovieCard(
                        movie = movie,
                        download = movieDownloadsByKey["$movieSourceId:${movie.movieId}"],
                        onOpen = { onOpenMovie(movieSourceId, movie.movieId) },
                        onPlayOffline = onPlayOfflineMovie,
                        onPause = onPauseMovie,
                        onResume = onResumeMovie,
                        onRetry = onRetryMovie,
                        onRemove = onRemoveMovie,
                        modifier = itemModifier(
                            libraryCatalogMovieFocusKey(movieSourceId, movie.movieId),
                        ),
                    )
                }
                gridItems(orphanedOfflineMovies, key = { "offline-movie:${it.downloadId}" }) { download ->
                    OfflineOnlyMovieCard(
                        download = download,
                        onPlay = { onPlayOfflineMovie(download) },
                        onRetry = { onRetryMovie(download) },
                        onRemove = { onRemoveMovie(download) },
                        modifier = itemModifier(libraryOfflineMovieFocusKey(download.downloadId)),
                    )
                }
            }

            if (filter != UnifiedLibraryFilter.MOVIES) {
                gridItems(visibleSeries, key = { "catalog-series:${it.seriesId}" }) { series ->
                    val seriesSourceId = sourceId ?: return@gridItems
                    val group = seriesGroupByIdentity["$seriesSourceId:${series.seriesId}"]
                    UnifiedSeriesCard(
                        series = series,
                        group = group,
                        offlineMode = offlineOnly,
                        onOpen = { onOpenCatalogSeries(seriesSourceId, series.seriesId, group) },
                        onOpenOfflineSeries = onOpenOfflineSeries,
                        modifier = itemModifier(
                            libraryCatalogSeriesFocusKey(seriesSourceId, series.seriesId),
                        ),
                    )
                }
                gridItems(orphanedOfflineSeries, key = { "offline-series:${it.key}" }) { group ->
                    LibrarySeriesCard(
                        group = group,
                        onOpenOfflineSeries = { onOpenOfflineSeries(group) },
                        modifier = itemModifier(libraryOfflineSeriesFocusKey(group)),
                    )
                }
            }
        }

        ContentViewMode.COMPACT -> LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = compactMinSize),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (filter != UnifiedLibraryFilter.SERIES) {
                gridItems(visibleMovies, key = { "compact-movie:${it.movieId}" }) { movie ->
                    val movieSourceId = sourceId ?: return@gridItems
                    CompactMovieCard(
                        movie = movie,
                        download = movieDownloadsByKey["$movieSourceId:${movie.movieId}"],
                        onOpen = { onOpenMovie(movieSourceId, movie.movieId) },
                        onPlayOffline = onPlayOfflineMovie,
                        onPause = onPauseMovie,
                        onResume = onResumeMovie,
                        onRetry = onRetryMovie,
                        onRemove = onRemoveMovie,
                        modifier = itemModifier(
                            libraryCatalogMovieFocusKey(movieSourceId, movie.movieId),
                        ),
                    )
                }
                gridItems(orphanedOfflineMovies, key = { "compact-offline-movie:${it.downloadId}" }) { download ->
                    CompactOfflineMovieCard(
                        download = download,
                        onPlay = { onPlayOfflineMovie(download) },
                        onRemove = { onRemoveMovie(download) },
                        modifier = itemModifier(libraryOfflineMovieFocusKey(download.downloadId)),
                    )
                }
            }

            if (filter != UnifiedLibraryFilter.MOVIES) {
                gridItems(visibleSeries, key = { "compact-series:${it.seriesId}" }) { series ->
                    val seriesSourceId = sourceId ?: return@gridItems
                    val group = seriesGroupByIdentity["$seriesSourceId:${series.seriesId}"]
                    CompactSeriesCard(
                        series = series,
                        group = group,
                        onOpen = { onOpenCatalogSeries(seriesSourceId, series.seriesId, group) },
                        onOpenOfflineSeries = onOpenOfflineSeries,
                        modifier = itemModifier(
                            libraryCatalogSeriesFocusKey(seriesSourceId, series.seriesId),
                        ),
                    )
                }
                gridItems(orphanedOfflineSeries, key = { "compact-offline-series:${it.key}" }) { group ->
                    CompactOfflineSeriesCard(
                        group = group,
                        onOpen = { onOpenOfflineSeries(group) },
                        modifier = itemModifier(libraryOfflineSeriesFocusKey(group)),
                    )
                }
            }
        }

        ContentViewMode.LIST -> LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            if (filter != UnifiedLibraryFilter.SERIES) {
                listItems(visibleMovies, key = { "list-movie:${it.movieId}" }) { movie ->
                    val movieSourceId = sourceId ?: return@listItems
                    MovieListRow(
                        movie = movie,
                        download = movieDownloadsByKey["$movieSourceId:${movie.movieId}"],
                        onOpen = { onOpenMovie(movieSourceId, movie.movieId) },
                        onPlayOffline = onPlayOfflineMovie,
                        onPause = onPauseMovie,
                        onResume = onResumeMovie,
                        onRetry = onRetryMovie,
                        onRemove = onRemoveMovie,
                        modifier = itemModifier(
                            libraryCatalogMovieFocusKey(movieSourceId, movie.movieId),
                        ),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
                }
                listItems(orphanedOfflineMovies, key = { "list-offline-movie:${it.downloadId}" }) { download ->
                    OfflineMovieListRow(
                        download = download,
                        onPlay = { onPlayOfflineMovie(download) },
                        onRemove = { onRemoveMovie(download) },
                        modifier = itemModifier(libraryOfflineMovieFocusKey(download.downloadId)),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
                }
            }

            if (filter != UnifiedLibraryFilter.MOVIES) {
                listItems(visibleSeries, key = { "list-series:${it.seriesId}" }) { series ->
                    val seriesSourceId = sourceId ?: return@listItems
                    val group = seriesGroupByIdentity["$seriesSourceId:${series.seriesId}"]
                    SeriesListRow(
                        series = series,
                        group = group,
                        offlineMode = offlineOnly,
                        onOpen = { onOpenCatalogSeries(seriesSourceId, series.seriesId, group) },
                        onOpenOfflineSeries = onOpenOfflineSeries,
                        modifier = itemModifier(
                            libraryCatalogSeriesFocusKey(seriesSourceId, series.seriesId),
                        ),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
                }
                listItems(orphanedOfflineSeries, key = { "list-offline-series:${it.key}" }) { group ->
                    OfflineSeriesListRow(
                        group = group,
                        onOpen = { onOpenOfflineSeries(group) },
                        modifier = itemModifier(libraryOfflineSeriesFocusKey(group)),
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 70.dp))
                }
            }
        }
    }
}

@Composable
private fun UnifiedMovieCard(
    movie: VodMovie,
    download: OfflineDownload?,
    onOpen: () -> Unit,
    onPlayOffline: (OfflineDownload) -> Unit,
    onPause: (OfflineDownload) -> Unit,
    onResume: (OfflineDownload) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RemotePoster(
                url = movie.posterUrl,
                title = movie.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = movie.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (download?.libraryOfflinePresentation()?.verifiedOffline == true) {
                LibraryOfflineBadge()
            }
            MovieStatusText(download = download)
            download?.let { managedDownload ->
                MovieDownloadActions(
                    download = managedDownload,
                    compact = false,
                    onPlay = onPlayOffline,
                    onPause = onPause,
                    onResume = onResume,
                    onRetry = onRetry,
                    onRemove = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun UnifiedSeriesCard(
    series: SeriesSummary,
    group: LibrarySeriesGroup?,
    offlineMode: Boolean,
    onOpen: () -> Unit,
    onOpenOfflineSeries: (LibrarySeriesGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group?.episodes?.count(OfflineDownload::countsForOfflineFilter) ?: 0
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RemotePoster(
                url = series.posterUrl,
                title = series.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = series.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            series.description?.trim()?.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (offlineEpisodes > 0) {
                LibraryOfflineBadge()
            }
            SeriesStatusText(
                offlineEpisodes = offlineEpisodes,
                offlineMode = offlineMode,
            )
            group?.let { managedGroup ->
                Button(
                    onClick = { onOpenOfflineSeries(managedGroup) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.DownloadDone, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Offline episodes", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun OfflineOnlyMovieCard(
    download: OfflineDownload,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RemotePoster(
                url = download.posterUrl,
                title = download.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LibraryOfflineBadge()
            Text(
                text = "${movieOfflineLabel(download)} · no longer in the active catalog",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (download.state) {
                    DownloadStates.COMPLETED -> Button(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Play Offline")
                    }
                    DownloadStates.FAILED -> Button(onClick = onRetry) { Text("Retry") }
                    else -> MovieStatusText(download = download)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                }
            }
        }
    }
}

@Composable
private fun CompactMovieCard(
    movie: VodMovie,
    download: OfflineDownload?,
    onOpen: () -> Unit,
    onPlayOffline: (OfflineDownload) -> Unit,
    onPause: (OfflineDownload) -> Unit,
    onResume: (OfflineDownload) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemotePoster(
                url = movie.posterUrl,
                title = movie.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = movie.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = movieOfflineLabel(download),
                style = MaterialTheme.typography.labelSmall,
                color = movieStatusColor(download),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            download?.let { managedDownload ->
                MovieDownloadActions(
                    download = managedDownload,
                    compact = true,
                    onPlay = onPlayOffline,
                    onPause = onPause,
                    onResume = onResume,
                    onRetry = onRetry,
                    onRemove = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CompactOfflineMovieCard(
    download: OfflineDownload,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemotePoster(
                url = download.posterUrl,
                title = download.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = download.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${movieOfflineLabel(download)} · Play Offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play offline")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                }
            }
        }
    }
}

@Composable
private fun CompactSeriesCard(
    series: SeriesSummary,
    group: LibrarySeriesGroup?,
    onOpen: () -> Unit,
    onOpenOfflineSeries: (LibrarySeriesGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group?.episodes?.count(OfflineDownload::countsForOfflineFilter) ?: 0
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemotePoster(
                url = series.posterUrl,
                title = series.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = series.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = librarySeriesOfflineLabel(offlineEpisodes) ?: "Series",
                style = MaterialTheme.typography.labelSmall,
                color = if (offlineEpisodes > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            group?.let { managedGroup ->
                Button(
                    onClick = { onOpenOfflineSeries(managedGroup) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("Offline", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun CompactOfflineSeriesCard(
    group: LibrarySeriesGroup,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group.episodes.count(OfflineDownload::countsForOfflineFilter)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemotePoster(
                url = group.posterUrl,
                title = group.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = requireNotNull(librarySeriesOfflineLabel(offlineEpisodes)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MovieListRow(
    movie: VodMovie,
    download: OfflineDownload?,
    onOpen: () -> Unit,
    onPlayOffline: (OfflineDownload) -> Unit,
    onPause: (OfflineDownload) -> Unit,
    onResume: (OfflineDownload) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemotePoster(
            url = movie.posterUrl,
            title = movie.name,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = movie.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MovieStatusText(download = download)
        }
        download?.let { managedDownload ->
            MovieDownloadActions(
                download = managedDownload,
                compact = false,
                onPlay = onPlayOffline,
                onPause = onPause,
                onResume = onResume,
                onRetry = onRetry,
                onRemove = onRemove,
                modifier = Modifier.width(144.dp),
            )
        }
    }
}

@Composable
private fun OfflineMovieListRow(
    download: OfflineDownload,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemotePoster(
            url = download.posterUrl,
            title = download.title,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${movieOfflineLabel(download)} · no longer in active catalog · Play Offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play offline")
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove download")
        }
    }
}

@Composable
private fun SeriesListRow(
    series: SeriesSummary,
    group: LibrarySeriesGroup?,
    offlineMode: Boolean,
    onOpen: () -> Unit,
    onOpenOfflineSeries: (LibrarySeriesGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group?.episodes?.count(OfflineDownload::countsForOfflineFilter) ?: 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemotePoster(
            url = series.posterUrl,
            title = series.name,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = series.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            series.description?.trim()?.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SeriesStatusText(
                offlineEpisodes = offlineEpisodes,
                offlineMode = offlineMode,
            )
        }
        group?.let { managedGroup ->
            Button(
                onClick = { onOpenOfflineSeries(managedGroup) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Offline episodes", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun OfflineSeriesListRow(
    group: LibrarySeriesGroup,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group.episodes.count(OfflineDownload::countsForOfflineFilter)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemotePoster(
            url = group.posterUrl,
            title = group.title,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = requireNotNull(librarySeriesOfflineLabel(offlineEpisodes)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MovieDownloadActions(
    download: OfflineDownload,
    compact: Boolean,
    onPlay: (OfflineDownload) -> Unit,
    onPause: (OfflineDownload) -> Unit,
    onResume: (OfflineDownload) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    when (download.state) {
                        DownloadStates.COMPLETED -> onPlay(download)
                        DownloadStates.DOWNLOADING,
                        DownloadStates.QUEUED,
                        -> onPause(download)
                        DownloadStates.PAUSED -> onResume(download)
                        DownloadStates.FAILED -> onRetry(download)
                    }
                },
            ) {
                Icon(
                    imageVector = when (download.state) {
                        DownloadStates.DOWNLOADING,
                        DownloadStates.QUEUED,
                        -> Icons.Filled.Pause
                        DownloadStates.FAILED -> Icons.Filled.Refresh
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = when (download.state) {
                        DownloadStates.COMPLETED -> "Play offline"
                        DownloadStates.DOWNLOADING,
                        DownloadStates.QUEUED,
                        -> "Pause download"
                        DownloadStates.PAUSED -> "Resume download"
                        DownloadStates.FAILED -> "Retry download"
                        else -> "Download action"
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onRemove(download) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove download")
            }
        }
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (download.state) {
            DownloadStates.COMPLETED -> Button(
                onClick = { onPlay(download) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play Offline", maxLines = 1, softWrap = false)
            }
            DownloadStates.DOWNLOADING,
            DownloadStates.QUEUED,
            -> FilledTonalButton(
                onClick = { onPause(download) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pause", maxLines = 1, softWrap = false)
            }
            DownloadStates.PAUSED -> FilledTonalButton(
                onClick = { onResume(download) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Resume", maxLines = 1, softWrap = false)
            }
            DownloadStates.FAILED -> FilledTonalButton(
                onClick = { onRetry(download) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Retry", maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onRemove(download) }) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove download")
        }
    }
}

@Composable
private fun MovieStatusText(download: OfflineDownload?) {
    Text(
        text = movieOfflineLabel(download),
        style = MaterialTheme.typography.labelSmall,
        color = movieStatusColor(download),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SeriesStatusText(
    offlineEpisodes: Int,
    offlineMode: Boolean,
) {
    Text(
        text = when {
            offlineEpisodes > 0 -> requireNotNull(librarySeriesOfflineLabel(offlineEpisodes))
            offlineMode -> "Not available offline"
            else -> "Series"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (offlineEpisodes > 0) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun movieStatusColor(download: OfflineDownload?) = when {
    download == null -> MaterialTheme.colorScheme.onSurfaceVariant
    download.isMissingFile() -> MaterialTheme.colorScheme.error
    download.state == DownloadStates.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

private fun OfflineDownload.countsForOfflineFilter(): Boolean =
    state == DownloadStates.COMPLETED && !isMissingFile()

private fun OfflineDownload.isMissingFile(): Boolean =
    state == DownloadStates.FAILED && failureReason == MISSING_FILE_REASON

private fun movieOfflineLabel(download: OfflineDownload?): String = when {
    download == null -> "Movie"
    download.isMissingFile() -> "File missing · Download again"
    download.state == DownloadStates.COMPLETED -> download.libraryOfflinePresentation().let { presentation ->
        "${presentation.badgeLabel} · ${presentation.storageLabel}"
    }
    download.state == DownloadStates.DOWNLOADING -> "Downloading"
    download.state == DownloadStates.QUEUED -> "Queued for download"
    download.state == DownloadStates.PAUSED -> "Download paused"
    download.state == DownloadStates.FAILED -> download.failureReason ?: "Download failed"
    else -> "Movie"
}

private fun libraryCatalogMovieFocusKey(sourceId: String, movieId: String): String =
    "movie:$sourceId:$movieId"

private fun libraryOfflineMovieFocusKey(downloadId: String): String =
    "offline-movie:$downloadId"

private fun libraryCatalogSeriesFocusKey(sourceId: String, seriesId: String): String =
    "series:$sourceId:$seriesId"

private fun libraryOfflineSeriesFocusKey(group: LibrarySeriesGroup): String =
    "offline-series:${group.key.sourceId}:${group.key.identity}"

private fun libraryVisibleFocusKeys(
    filter: UnifiedLibraryFilter,
    sourceId: String?,
    visibleMovies: List<VodMovie>,
    orphanedOfflineMovies: List<OfflineDownload>,
    visibleSeries: List<SeriesSummary>,
    orphanedOfflineSeries: List<LibrarySeriesGroup>,
): List<String> = buildList {
    val resolvedSourceId = sourceId ?: return@buildList
    if (filter != UnifiedLibraryFilter.SERIES) {
        visibleMovies.forEach { movie ->
            add(libraryCatalogMovieFocusKey(resolvedSourceId, movie.movieId))
        }
        orphanedOfflineMovies.forEach { download ->
            add(libraryOfflineMovieFocusKey(download.downloadId))
        }
    }
    if (filter != UnifiedLibraryFilter.MOVIES) {
        visibleSeries.forEach { series ->
            add(libraryCatalogSeriesFocusKey(resolvedSourceId, series.seriesId))
        }
        orphanedOfflineSeries.forEach { group ->
            add(libraryOfflineSeriesFocusKey(group))
        }
    }
}

internal fun shouldShowMobileLibraryInitialLoading(
    isTelevision: Boolean,
    offlineOnly: Boolean,
    hasItems: Boolean,
    refreshing: Boolean,
    initialRefreshPending: Boolean,
): Boolean =
    !isTelevision &&
        !offlineOnly &&
        !hasItems &&
        (refreshing || initialRefreshPending)

private suspend fun enqueueSeriesEpisode(
    downloadRuntime: OfflineDownloadFeatureRuntime,
    sourceId: String,
    episode: SeriesEpisode,
) {
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
