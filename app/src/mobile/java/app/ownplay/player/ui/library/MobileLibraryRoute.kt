package app.ownplay.player.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.source.SourceResult
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import kotlinx.coroutines.flow.flowOf

private enum class MobileLibraryFilter {
    MOVIES,
    SERIES,
}

@Composable
internal fun MobileLibraryRoute(
    sourceId: String?,
    sourceKind: String?,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
) {
    val context = LocalContext.current
    val vodRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
    val seriesRuntime = remember(context) { SeriesFeatureRuntime(context.applicationContext) }

    DisposableEffect(vodRuntime, seriesRuntime) {
        onDispose {
            vodRuntime.close()
            seriesRuntime.close()
        }
    }

    val vodFlow = remember(sourceId, vodRuntime) {
        sourceId?.let(vodRuntime::observeCatalog) ?: flowOf(VodCatalog())
    }
    val seriesFlow = remember(sourceId, seriesRuntime) {
        sourceId?.let(seriesRuntime::observeCatalog) ?: flowOf(SeriesCatalog())
    }
    val vodCatalog by vodFlow.collectAsState(initial = VodCatalog())
    val seriesCatalog by seriesFlow.collectAsState(initial = SeriesCatalog())

    var filter by remember { mutableStateOf(MobileLibraryFilter.MOVIES) }
    var movieCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var seriesCategoryKey by remember(sourceId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var refreshing by remember(sourceId, sourceKind) { mutableStateOf(false) }
    var refreshWarning by remember(sourceId, sourceKind) { mutableStateOf(false) }

    LaunchedEffect(sourceId, sourceKind, vodRuntime, seriesRuntime) {
        val resolvedSourceId = sourceId
        if (resolvedSourceId == null || sourceKind != SourceKinds.XTREAM) {
            refreshing = false
            refreshWarning = false
            return@LaunchedEffect
        }

        refreshing = true
        refreshWarning = false
        try {
            val vodResult = vodRuntime.refresh(resolvedSourceId)
            val seriesResult = seriesRuntime.refresh(resolvedSourceId)
            refreshWarning =
                vodResult is SourceResult.Failure || seriesResult is SourceResult.Failure
        } finally {
            refreshing = false
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

    val normalizedQuery = query.trim().lowercase()
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
                val categoryMatch = movieCategoryKey == null || movie.categoryKey == movieCategoryKey
                val queryMatch = normalizedQuery.isBlank() || movie.name.lowercase().contains(normalizedQuery)
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
                val categoryMatch = seriesCategoryKey == null || series.categoryKey == seriesCategoryKey
                val queryMatch = normalizedQuery.isBlank() || series.name.lowercase().contains(normalizedQuery)
                categoryMatch && queryMatch
            }
        }
    }
    val hasItems = when (filter) {
        MobileLibraryFilter.MOVIES -> visibleMovies.isNotEmpty()
        MobileLibraryFilter.SERIES -> visibleSeries.isNotEmpty()
    }
    val showInitialLoading =
        sourceId != null &&
            sourceKind == SourceKinds.XTREAM &&
            refreshing &&
            !hasItems

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Movies and Series from your active playlist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
            rowItems(
                items = MobileLibraryFilter.entries,
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
                                MobileLibraryFilter.MOVIES -> "Movies"
                                MobileLibraryFilter.SERIES -> "Series"
                            },
                        )
                    },
                )
            }
            if (refreshing && !showInitialLoading) {
                item(key = "library-refreshing") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        when (filter) {
            MobileLibraryFilter.MOVIES -> MobileLibraryCategoryStrip(
                selectedCategoryKey = movieCategoryKey,
                categories = vodCatalog.categories.map { it.providerCategoryKey to it.name },
                onCategorySelected = { movieCategoryKey = it },
            )
            MobileLibraryFilter.SERIES -> MobileLibraryCategoryStrip(
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
                            MobileLibraryFilter.MOVIES -> "Search Movies"
                            MobileLibraryFilter.SERIES -> "Search Series"
                        },
                    )
                },
                shape = RoundedCornerShape(10.dp),
            )
        }

        if (refreshWarning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                    Text(
                        text = "Library refresh failed. Showing the saved catalog where available.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (filter == MobileLibraryFilter.MOVIES && normalizedQuery.isBlank() && sourceId != null) {
            LibraryMovieContinueWatchingStrip(
                movies = vodCatalog.continueWatching,
                onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
            )
        }
        if (filter == MobileLibraryFilter.SERIES && normalizedQuery.isBlank() && sourceId != null) {
            LibrarySeriesContinueWatchingStrip(
                episodes = seriesCatalog.continueWatching,
                onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
            )
        }

        when {
            sourceId == null -> MobileLibraryEmptyState(
                title = "No playlist configured",
                detail = "Add a playlist in Settings to load Movies and Series.",
                modifier = Modifier.weight(1f),
            )
            sourceKind != SourceKinds.XTREAM -> MobileLibraryEmptyState(
                title = "Library unavailable for this source",
                detail = "Movies and Series require an Xtream-compatible source.",
                modifier = Modifier.weight(1f),
            )
            showInitialLoading -> MobileLibraryLoadingState(modifier = Modifier.weight(1f))
            !hasItems -> MobileLibraryEmptyState(
                title = "No matching media",
                detail = "Try another category, Library section or search term.",
                modifier = Modifier.weight(1f),
            )
            else -> MobileLibraryCatalogGrid(
                filter = filter,
                sourceId = sourceId,
                movies = visibleMovies,
                series = visibleSeries,
                onOpenMovieDetails = onOpenMovieDetails,
                onOpenSeriesDetails = onOpenSeriesDetails,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MobileLibraryCategoryStrip(
    selectedCategoryKey: String?,
    categories: List<Pair<String, String>>,
    onCategorySelected: (String) -> Unit,
) {
    if (categories.isEmpty()) return

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        rowItems(categories, key = { it.first }) { (categoryKey, categoryName) ->
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
private fun MobileLibraryCatalogGrid(
    filter: MobileLibraryFilter,
    sourceId: String,
    movies: List<VodMovie>,
    series: List<SeriesSummary>,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (filter) {
            MobileLibraryFilter.MOVIES -> gridItems(
                items = movies,
                key = { it.movieId },
            ) { movie ->
                MobileLibraryPosterCard(
                    title = movie.name,
                    posterUrl = movie.posterUrl,
                    onOpen = { onOpenMovieDetails(sourceId, movie.movieId) },
                )
            }
            MobileLibraryFilter.SERIES -> gridItems(
                items = series,
                key = { it.seriesId },
            ) { item ->
                MobileLibraryPosterCard(
                    title = item.name,
                    posterUrl = item.posterUrl,
                    onOpen = { onOpenSeriesDetails(sourceId, item.seriesId) },
                )
            }
        }
    }
}

@Composable
private fun MobileLibraryPosterCard(
    title: String,
    posterUrl: String?,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RemotePoster(
                url = posterUrl,
                title = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MobileLibraryLoadingState(modifier: Modifier = Modifier) {
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
        }
    }
}

@Composable
private fun MobileLibraryEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
