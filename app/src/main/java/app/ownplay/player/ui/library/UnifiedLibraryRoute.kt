package app.ownplay.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.download.OfflineDownloadFeatureRuntime
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.source.SourceResult
import app.ownplay.player.ui.theme.OwnPlayMediaLayout
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import kotlinx.coroutines.flow.flowOf

internal enum class UnifiedLibraryFilter {
    MOVIES,
    SERIES,
}

internal val libraryCatalogSections = listOf(
    UnifiedLibraryFilter.MOVIES,
    UnifiedLibraryFilter.SERIES,
)

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun UnifiedLibraryRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String?,
    sourceKind: String?,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadRuntime = remember(context) {
        OfflineDownloadFeatureRuntime(context.applicationContext)
    }
    val vodRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
    val seriesRuntime = remember(context) { SeriesFeatureRuntime(context.applicationContext) }

    DisposableEffect(downloadRuntime, vodRuntime, seriesRuntime) {
        onDispose {
            downloadRuntime.close()
            vodRuntime.close()
            seriesRuntime.close()
        }
    }

    val downloads by downloadRuntime.observeAll().collectAsState(initial = emptyList())
    val vodFlow = remember(sourceId, vodRuntime) {
        sourceId?.let(vodRuntime::observeCatalog) ?: flowOf(VodCatalog())
    }
    val seriesFlow = remember(sourceId, seriesRuntime) {
        sourceId?.let(seriesRuntime::observeCatalog) ?: flowOf(SeriesCatalog())
    }
    val vodCatalog by vodFlow.collectAsState(initial = VodCatalog())
    val seriesCatalog by seriesFlow.collectAsState(initial = SeriesCatalog())

    var filter by remember { mutableStateOf(UnifiedLibraryFilter.MOVIES) }
    var movieCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var seriesCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
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
    }
    val refreshWarning = when (filter) {
        UnifiedLibraryFilter.MOVIES -> vodRefreshWarning
        UnifiedLibraryFilter.SERIES -> seriesRefreshWarning
    }

    LaunchedEffect(vodCatalog.categories, movieCategoryKey) {
        val categories = vodCatalog.categories
        movieCategoryKey = when {
            categories.isEmpty() -> null
            movieCategoryKey != null && categories.any {
                it.providerCategoryKey == movieCategoryKey
            } -> movieCategoryKey
            else -> categories.first().providerCategoryKey
        }
    }

    LaunchedEffect(seriesCatalog.categories, seriesCategoryKey) {
        val categories = seriesCatalog.categories
        seriesCategoryKey = when {
            categories.isEmpty() -> null
            seriesCategoryKey != null && categories.any {
                it.providerCategoryKey == seriesCategoryKey
            } -> seriesCategoryKey
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

    val normalizedQuery = query.trim().lowercase()
    val movieDownloadsByKey = remember(downloads) {
        downloads
            .filter { it.mediaKind == DownloadMediaKinds.MOVIE }
            .associateBy { "${it.sourceId}:${it.contentId}" }
    }
    val seriesGroups = remember(downloads) { groupLibrarySeries(downloads) }
    val seriesGroupByIdentity = remember(seriesGroups) {
        seriesGroups.mapNotNull { group ->
            group.seriesId?.let { seriesId -> "${group.key.sourceId}:$seriesId" to group }
        }.toMap()
    }

    val visibleMovies = remember(
        vodCatalog.movies,
        sourceId,
        movieCategoryKey,
        normalizedQuery,
    ) {
        if (sourceId == null) {
            emptyList()
        } else {
            vodCatalog.movies.filter { movie ->
                val categoryMatch =
                    movieCategoryKey == null || movie.categoryKey == movieCategoryKey
                val queryMatch =
                    normalizedQuery.isBlank() || movie.name.lowercase().contains(normalizedQuery)
                categoryMatch && queryMatch
            }
        }
    }
    val visibleSeries = remember(
        seriesCatalog.series,
        sourceId,
        seriesCategoryKey,
        normalizedQuery,
    ) {
        if (sourceId == null) {
            emptyList()
        } else {
            seriesCatalog.series.filter { series ->
                val categoryMatch =
                    seriesCategoryKey == null || series.categoryKey == seriesCategoryKey
                val queryMatch =
                    normalizedQuery.isBlank() || series.name.lowercase().contains(normalizedQuery)
                categoryMatch && queryMatch
            }
        }
    }

    val continueWatching = remember(vodCatalog.continueWatching, seriesCatalog.continueWatching) {
        unifiedContinueWatching(
            movies = vodCatalog.continueWatching,
            episodes = seriesCatalog.continueWatching,
        )
    }
    val showContinueWatching =
        filter == UnifiedLibraryFilter.MOVIES &&
            normalizedQuery.isBlank() &&
            continueWatching.isNotEmpty()
    val mediaCount = when (filter) {
        UnifiedLibraryFilter.MOVIES -> visibleMovies.size
        UnifiedLibraryFilter.SERIES -> visibleSeries.size
    }
    val hasItems = mediaCount > 0 || showContinueWatching
    val showInitialMobileLoading = shouldShowMobileLibraryInitialLoading(
        offlineOnly = false,
        hasItems = hasItems,
        refreshing = refreshing,
        initialRefreshPending = initialCatalogRefreshPending,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Library",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (refreshing && !showInitialMobileLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
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

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listItems(
                items = libraryCatalogSections,
                key = { it.name },
            ) { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = {
                        filter = option
                        query = ""
                        searchExpanded = false
                    },
                    label = {
                        Text(
                            when (option) {
                                UnifiedLibraryFilter.MOVIES -> "Movies"
                                UnifiedLibraryFilter.SERIES -> "Series"
                            },
                        )
                    },
                )
            }
        }

        when (filter) {
            UnifiedLibraryFilter.MOVIES -> LibraryCategoryStrip(
                selectedCategoryKey = movieCategoryKey,
                categories = vodCatalog.categories.map { it.providerCategoryKey to it.name },
                onCategorySelected = { movieCategoryKey = it },
            )
            UnifiedLibraryFilter.SERIES -> LibraryCategoryStrip(
                selectedCategoryKey = seriesCategoryKey,
                categories = seriesCatalog.categories.map { it.providerCategoryKey to it.name },
                onCategorySelected = { seriesCategoryKey = it },
            )
        }

        if (searchExpanded || query.isNotBlank()) {
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
                            UnifiedLibraryFilter.MOVIES -> "Search Movies"
                            UnifiedLibraryFilter.SERIES -> "Search Series"
                        },
                    )
                },
                shape = RoundedCornerShape(10.dp),
            )
        }

        if (showContinueWatching && sourceId != null) {
            LibraryUnifiedContinueWatchingStrip(
                items = continueWatching,
                onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
                onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
            )
        }

        if (refreshWarning) {
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

        if (showInitialMobileLoading) {
            LibraryLoadingState(modifier = Modifier.weight(1f))
            return
        }

        if (!hasItems) {
            LibraryEmptyState(
                sourceKind = sourceKind,
                modifier = Modifier.weight(1f),
            )
            return
        }

        LibraryCatalogView(
            filter = filter,
            sourceId = sourceId,
            visibleMovies = visibleMovies,
            visibleSeries = visibleSeries,
            movieDownloadsByKey = movieDownloadsByKey,
            seriesGroupByIdentity = seriesGroupByIdentity,
            onOpenMovie = onOpenMovieDetails,
            onOpenSeries = onOpenSeriesDetails,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibraryCategoryStrip(
    selectedCategoryKey: String?,
    categories: List<Pair<String, String>>,
    onCategorySelected: (String?) -> Unit,
) {
    if (categories.isEmpty()) return
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
    sourceKind: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "No matching media",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (sourceKind != SourceKinds.XTREAM) {
                    "Movies and Series require an Xtream-compatible source."
                } else {
                    "Try another category, section or search term."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryCatalogView(
    filter: UnifiedLibraryFilter,
    sourceId: String?,
    visibleMovies: List<VodMovie>,
    visibleSeries: List<SeriesSummary>,
    movieDownloadsByKey: Map<String, OfflineDownload>,
    seriesGroupByIdentity: Map<String, LibrarySeriesGroup>,
    onOpenMovie: (sourceId: String, movieId: String) -> Unit,
    onOpenSeries: (sourceId: String, seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = OwnPlayMediaLayout.MinimumPosterWidthDp.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(OwnPlayMediaLayout.GridGapDp.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (filter) {
            UnifiedLibraryFilter.MOVIES -> {
                gridItems(visibleMovies, key = { "catalog-movie:${it.movieId}" }) { movie ->
                    val resolvedSourceId = sourceId ?: return@gridItems
                    UnifiedMovieCard(
                        movie = movie,
                        download = movieDownloadsByKey["$resolvedSourceId:${movie.movieId}"],
                        onOpen = { onOpenMovie(resolvedSourceId, movie.movieId) },
                    )
                }
            }
            UnifiedLibraryFilter.SERIES -> {
                gridItems(visibleSeries, key = { "catalog-series:${it.seriesId}" }) { series ->
                    val resolvedSourceId = sourceId ?: return@gridItems
                    UnifiedSeriesCard(
                        series = series,
                        group = seriesGroupByIdentity["$resolvedSourceId:${series.seriesId}"],
                        onOpen = { onOpenSeries(resolvedSourceId, series.seriesId) },
                    )
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
    modifier: Modifier = Modifier,
) {
    val progress = progressFraction(movie.positionMs, movie.durationMs)
    val verifiedOffline = download?.libraryOfflinePresentation()?.verifiedOffline == true

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RemotePoster(
            url = movie.posterUrl,
            title = movie.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(OwnPlayMediaLayout.PosterAspectRatio),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        ) {
            if (progress != null && !movie.progressCompleted) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = movie.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            movie.resumeAvailable -> Text(
                text = continueWatchingResumeLabel(movie.positionMs, movie.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            movie.progressCompleted -> Text(
                text = "Watched",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            verifiedOffline -> Text(
                text = "Available offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun UnifiedSeriesCard(
    series: SeriesSummary,
    group: LibrarySeriesGroup?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offlineEpisodes = group?.episodes?.count { episode ->
        episode.libraryOfflinePresentation().verifiedOffline
    } ?: 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RemotePoster(
            url = series.posterUrl,
            title = series.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(OwnPlayMediaLayout.PosterAspectRatio),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        )
        Text(
            text = series.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (offlineEpisodes > 0) {
            Text(
                text = librarySeriesOfflineLabel(offlineEpisodes) ?: "Available offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun shouldShowMobileLibraryInitialLoading(
    offlineOnly: Boolean,
    hasItems: Boolean,
    refreshing: Boolean,
    initialRefreshPending: Boolean,
): Boolean =
    !offlineOnly &&
        !hasItems &&
        (refreshing || initialRefreshPending)
