package app.ownplay.mobile.feature.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.domain.LibraryBrowsePolicy
import app.ownplay.mobile.feature.library.domain.LibraryCatalogSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryCategory
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingItem
import app.ownplay.mobile.feature.library.domain.LibraryMovieSummary
import app.ownplay.mobile.feature.library.domain.LibrarySeriesSummary
import app.ownplay.mobile.feature.library.domain.LibrarySortOption
import app.ownplay.mobile.sources.domain.SourceSummary

private enum class LibraryPage {
    HOME,
    CONTINUE_WATCHING,
    MOVIE_CATEGORIES,
    MOVIE_GRID,
    SERIES_CATEGORIES,
    SERIES_GRID,
    FAVORITES,
    SEARCH,
}

private enum class LibraryFavoriteKind {
    MOVIES,
    SERIES,
}

@Composable
internal fun LibraryCatalogContent(
    source: SourceSummary,
    catalog: LibraryCatalogSnapshot,
    artworkLoader: LibraryArtworkLoader,
    compactMediaRows: Boolean,
    showCategoryFlags: Boolean,
    hideCategoryPrefix: Boolean,
    catalogLoadError: String?,
    catalogLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenContinueWatching: (LibraryContinueWatchingItem) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
    showDownloads: Boolean,
    onOpenDownloads: () -> Unit,
    onRemoveContinueWatching: (LibraryContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageName by rememberSaveable(source.sourceId.value) {
        mutableStateOf(LibraryPage.HOME.name)
    }
    var sortName by rememberSaveable(source.sourceId.value) {
        mutableStateOf(LibrarySortOption.PROVIDER_ORDER.name)
    }
    var movieCategoryId by rememberSaveable(source.sourceId.value) { mutableStateOf<String?>(null) }
    var seriesCategoryId by rememberSaveable(source.sourceId.value) { mutableStateOf<String?>(null) }
    var favoriteKindName by rememberSaveable(source.sourceId.value) {
        mutableStateOf(LibraryFavoriteKind.MOVIES.name)
    }

    val page = LibraryPage.entries.firstOrNull { it.name == pageName } ?: LibraryPage.HOME
    val sort = LibrarySortOption.entries.firstOrNull { it.name == sortName }
        ?: LibrarySortOption.PROVIDER_ORDER
    val favoriteKind = LibraryFavoriteKind.entries.firstOrNull { it.name == favoriteKindName }
        ?: LibraryFavoriteKind.MOVIES

    val movieCategory = catalog.movieCategories.firstOrNull { it.categoryId == movieCategoryId }
    val seriesCategory = catalog.seriesCategories.firstOrNull { it.categoryId == seriesCategoryId }
    val movieCategoryLabelById = remember(
        catalog.movieCategories,
        showCategoryFlags,
        hideCategoryPrefix,
    ) {
        catalog.movieCategories.associate { category ->
            category.categoryId to ProviderCategoryDisplayPolicy.label(
                rawName = category.displayName,
                hideRegionPrefix = hideCategoryPrefix,
                showFlag = showCategoryFlags,
            )
        }
    }
    val seriesCategoryLabelById = remember(
        catalog.seriesCategories,
        showCategoryFlags,
        hideCategoryPrefix,
    ) {
        catalog.seriesCategories.associate { category ->
            category.categoryId to ProviderCategoryDisplayPolicy.label(
                rawName = category.displayName,
                hideRegionPrefix = hideCategoryPrefix,
                showFlag = showCategoryFlags,
            )
        }
    }
    val hasMovieCategories = catalog.movieCategories.isNotEmpty()
    val hasSeriesCategories = catalog.seriesCategories.isNotEmpty()

    val movieGridItems = remember(catalog.movies, sort, movieCategoryId) {
        LibraryBrowsePolicy.movies(
            items = catalog.movies,
            query = "",
            sort = sort,
            favoritesOnly = false,
            categoryId = movieCategoryId,
        )
    }
    val seriesGridItems = remember(catalog.series, sort, seriesCategoryId) {
        LibraryBrowsePolicy.series(
            items = catalog.series,
            query = "",
            sort = sort,
            favoritesOnly = false,
            categoryId = seriesCategoryId,
        )
    }
    val favoriteMovies = remember(catalog.movies, sort) {
        LibraryBrowsePolicy.movies(
            items = catalog.movies,
            query = "",
            sort = sort,
            favoritesOnly = true,
            categoryId = null,
        )
    }
    val favoriteSeries = remember(catalog.series, sort) {
        LibraryBrowsePolicy.series(
            items = catalog.series,
            query = "",
            sort = sort,
            favoritesOnly = true,
            categoryId = null,
        )
    }
    val searchMovies = remember(catalog.movies, searchQuery, sort) {
        if (searchQuery.isBlank()) emptyList() else LibraryBrowsePolicy.movies(
            items = catalog.movies,
            query = searchQuery,
            sort = sort,
            favoritesOnly = false,
            categoryId = null,
        )
    }
    val searchSeries = remember(catalog.series, searchQuery, sort) {
        if (searchQuery.isBlank()) emptyList() else LibraryBrowsePolicy.series(
            items = catalog.series,
            query = searchQuery,
            sort = sort,
            favoritesOnly = false,
            categoryId = null,
        )
    }

    val homeListState = rememberLazyListState()
    val continueWatchingListState = rememberLazyListState()
    val movieCategoryListState = rememberLazyListState()
    val seriesCategoryListState = rememberLazyListState()
    val movieGridState = rememberLazyGridState()
    val seriesGridState = rememberLazyGridState()
    val favoriteMoviesGridState = rememberLazyGridState()
    val favoriteSeriesGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()

    BackHandler(enabled = page != LibraryPage.HOME) {
        pageName = when (page) {
            LibraryPage.MOVIE_GRID -> {
                if (hasMovieCategories) LibraryPage.MOVIE_CATEGORIES.name else LibraryPage.HOME.name
            }
            LibraryPage.SERIES_GRID -> {
                if (hasSeriesCategories) LibraryPage.SERIES_CATEGORIES.name else LibraryPage.HOME.name
            }
            else -> LibraryPage.HOME.name
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (page) {
            LibraryPage.HOME -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "Library",
                            color = OwnPlayColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = source.displayName,
                            color = OwnPlayColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row {
                        TextButton(onClick = { pageName = LibraryPage.FAVORITES.name }) {
                            Text("★ Favorites")
                        }
                        TextButton(onClick = { pageName = LibraryPage.SEARCH.name }) {
                            Text("Search")
                        }
                    }
                }
            }
            LibraryPage.CONTINUE_WATCHING -> {
                LibraryPageHeader(
                    title = "Continue Watching",
                    onBack = { pageName = LibraryPage.HOME.name },
                )
            }
            LibraryPage.MOVIE_CATEGORIES -> {
                LibraryPageHeader(
                    title = "Movies",
                    onBack = { pageName = LibraryPage.HOME.name },
                )
            }
            LibraryPage.MOVIE_GRID -> {
                LibraryPageHeader(
                    title = movieCategory
                        ?.categoryId
                        ?.let(movieCategoryLabelById::get)
                        ?: "Movies",
                    onBack = {
                        pageName = if (hasMovieCategories) {
                            LibraryPage.MOVIE_CATEGORIES.name
                        } else {
                            LibraryPage.HOME.name
                        }
                    },
                )
                LibrarySortControls(
                    selected = sort,
                    onSelected = { sortName = it.name },
                )
            }
            LibraryPage.SERIES_CATEGORIES -> {
                LibraryPageHeader(
                    title = "Series",
                    onBack = { pageName = LibraryPage.HOME.name },
                )
            }
            LibraryPage.SERIES_GRID -> {
                LibraryPageHeader(
                    title = seriesCategory
                        ?.categoryId
                        ?.let(seriesCategoryLabelById::get)
                        ?: "Series",
                    onBack = {
                        pageName = if (hasSeriesCategories) {
                            LibraryPage.SERIES_CATEGORIES.name
                        } else {
                            LibraryPage.HOME.name
                        }
                    },
                )
                LibrarySortControls(
                    selected = sort,
                    onSelected = { sortName = it.name },
                )
            }
            LibraryPage.FAVORITES -> {
                LibraryPageHeader(
                    title = "Favorites",
                    onBack = { pageName = LibraryPage.HOME.name },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { favoriteKindName = LibraryFavoriteKind.MOVIES.name }) {
                        Text(if (favoriteKind == LibraryFavoriteKind.MOVIES) "Movies ✓" else "Movies")
                    }
                    TextButton(onClick = { favoriteKindName = LibraryFavoriteKind.SERIES.name }) {
                        Text(if (favoriteKind == LibraryFavoriteKind.SERIES) "Series ✓" else "Series")
                    }
                }
            }
            LibraryPage.SEARCH -> {
                LibraryPageHeader(
                    title = "Search Library",
                    onBack = { pageName = LibraryPage.HOME.name },
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Movies and Series") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (catalogLoading) {
            Text(text = "Importing Library catalog…", color = OwnPlayColors.TextMuted)
        }
        catalogLoadError?.let { message ->
            Text(text = message, color = OwnPlayColors.Error)
        }

        Box(modifier = Modifier.weight(1f)) {
            when (page) {
                LibraryPage.HOME -> LibraryHomeContent(
                    catalog = catalog,
                    catalogLoading = catalogLoading,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    listState = homeListState,
                    onOpenContinueWatching = onOpenContinueWatching,
                    onRemoveContinueWatching = onRemoveContinueWatching,
                    showDownloads = showDownloads,
                    onOpenDownloads = onOpenDownloads,
                    onShowContinueWatching = { pageName = LibraryPage.CONTINUE_WATCHING.name },
                    onShowMovies = {
                        movieCategoryId = null
                        pageName = if (hasMovieCategories) {
                            LibraryPage.MOVIE_CATEGORIES.name
                        } else {
                            LibraryPage.MOVIE_GRID.name
                        }
                    },
                    onShowSeries = {
                        seriesCategoryId = null
                        pageName = if (hasSeriesCategories) {
                            LibraryPage.SERIES_CATEGORIES.name
                        } else {
                            LibraryPage.SERIES_GRID.name
                        }
                    },
                    onShowFavorites = { pageName = LibraryPage.FAVORITES.name },
                )
                LibraryPage.CONTINUE_WATCHING -> LibraryContinueWatchingAllContent(
                    items = catalog.continueWatching,
                    listState = continueWatchingListState,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    onOpen = onOpenContinueWatching,
                    onRemove = onRemoveContinueWatching,
                )
                LibraryPage.MOVIE_CATEGORIES -> LibraryCategoryList(
                    categories = catalog.movieCategories,
                    listState = movieCategoryListState,
                    itemCount = { categoryId ->
                        catalog.movies.count { it.categoryId == categoryId }
                    },
                    categoryLabel = { category ->
                        movieCategoryLabelById[category.categoryId] ?: category.displayName
                    },
                    emptyMessage = "No Movie categories are available from this source.",
                    onOpen = { category ->
                        movieCategoryId = category.categoryId
                        pageName = LibraryPage.MOVIE_GRID.name
                    },
                )
                LibraryPage.MOVIE_GRID -> LibraryMovieGridContent(
                    movies = movieGridItems,
                    gridState = movieGridState,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    onOpenMovie = onOpenMovie,
                    onToggleFavorite = onToggleFavorite,
                )
                LibraryPage.SERIES_CATEGORIES -> LibraryCategoryList(
                    categories = catalog.seriesCategories,
                    listState = seriesCategoryListState,
                    itemCount = { categoryId ->
                        catalog.series.count { it.categoryId == categoryId }
                    },
                    categoryLabel = { category ->
                        seriesCategoryLabelById[category.categoryId] ?: category.displayName
                    },
                    emptyMessage = "No Series categories are available from this source.",
                    onOpen = { category ->
                        seriesCategoryId = category.categoryId
                        pageName = LibraryPage.SERIES_GRID.name
                    },
                )
                LibraryPage.SERIES_GRID -> LibrarySeriesGridContent(
                    series = seriesGridItems,
                    gridState = seriesGridState,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    onOpenSeries = onOpenSeries,
                    onToggleFavorite = onToggleFavorite,
                )
                LibraryPage.FAVORITES -> LibraryFavoritesContent(
                    kind = favoriteKind,
                    movies = favoriteMovies,
                    series = favoriteSeries,
                    movieGridState = favoriteMoviesGridState,
                    seriesGridState = favoriteSeriesGridState,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    onToggleFavorite = onToggleFavorite,
                )
                LibraryPage.SEARCH -> LibrarySearchContent(
                    query = searchQuery,
                    movies = searchMovies,
                    series = searchSeries,
                    listState = searchListState,
                    artworkLoader = artworkLoader,
                    compact = compactMediaRows,
                    showProviderFlags = showCategoryFlags,
                    hideProviderPrefix = hideCategoryPrefix,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun LibraryPageHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(
            text = title,
            color = OwnPlayColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibrarySortControls(
    selected: LibrarySortOption,
    onSelected: (LibrarySortOption) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(
                text = "Sort:",
                color = OwnPlayColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(LibrarySortOption.entries, key = { it.name }) { option ->
            TextButton(onClick = { onSelected(option) }) {
                val label = when (option) {
                    LibrarySortOption.PROVIDER_ORDER -> "Provider"
                    LibrarySortOption.TITLE_ASC -> "A–Z"
                    LibrarySortOption.RATING_DESC -> "Rating"
                }
                Text(if (selected == option) label + " ✓" else label)
            }
        }
    }
}

@Composable
private fun LibraryHomeContent(
    catalog: LibraryCatalogSnapshot,
    catalogLoading: Boolean,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenContinueWatching: (LibraryContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (LibraryContinueWatchingItem) -> Unit,
    showDownloads: Boolean,
    onOpenDownloads: () -> Unit,
    onShowContinueWatching: () -> Unit,
    onShowMovies: () -> Unit,
    onShowSeries: () -> Unit,
    onShowFavorites: () -> Unit,
) {
    val continueWatching = LibraryBrowsePolicy.continueWatching(catalog.continueWatching, "")
    val favoriteCount = catalog.movies.count(LibraryMovieSummary::favorite) +
        catalog.series.count(LibrarySeriesSummary::favorite)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (continueWatching.isNotEmpty()) {
            item {
                LibraryContinueWatchingShelf(
                    items = continueWatching,
                    artworkLoader = artworkLoader,
                    compact = compact,
                    showProviderFlags = showProviderFlags,
                    hideProviderPrefix = hideProviderPrefix,
                    title = "Continue Watching",
                    onOpen = onOpenContinueWatching,
                    onRemove = onRemoveContinueWatching,
                    onViewAll = onShowContinueWatching,
                )
            }
        }

        item {
            LibraryHomeNavigationCard(
                title = "Movies",
                subtitle = catalog.movieCategories.size.toString() + " provider categories • " +
                    catalog.movies.size.toString() + " titles",
                onClick = onShowMovies,
            )
        }
        item {
            LibraryHomeNavigationCard(
                title = "Series",
                subtitle = catalog.seriesCategories.size.toString() + " provider categories • " +
                    catalog.series.size.toString() + " titles",
                onClick = onShowSeries,
            )
        }
        item {
            LibraryHomeNavigationCard(
                title = "Favorites",
                subtitle = favoriteCount.toString() + " saved items",
                onClick = onShowFavorites,
            )
        }
        if (showDownloads) {
            item {
                LibraryHomeNavigationCard(
                    title = "Downloads / Offline",
                    subtitle = "Open downloaded and active media.",
                    onClick = onOpenDownloads,
                )
            }
        }
        if (
            !catalogLoading &&
            catalog.movies.isEmpty() &&
            catalog.series.isEmpty() &&
            continueWatching.isEmpty()
        ) {
            item {
                Text(
                    text = "No Movies or Series are available from this source.",
                    color = OwnPlayColors.TextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LibraryHomeNavigationCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        color = OwnPlayColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = subtitle, color = OwnPlayColors.TextSecondary)
            }
            Text("›", color = OwnPlayColors.TextMuted)
        }
    }
}

@Composable
private fun LibraryCategoryList(
    categories: List<LibraryCategory>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: (String) -> Int,
    categoryLabel: (LibraryCategory) -> String,
    emptyMessage: String,
    onOpen: (LibraryCategory) -> Unit,
) {
    val ordered = remember(categories) {
        categories.sortedWith(
            compareBy<LibraryCategory>(LibraryCategory::providerOrder)
                .thenBy(LibraryCategory::categoryId),
        )
    }

    if (ordered.isEmpty()) {
        Text(
            text = emptyMessage,
            color = OwnPlayColors.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(ordered, key = { it.categoryId }) { category ->
            Surface(
                color = OwnPlayColors.SurfaceRaised,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(category) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = categoryLabel(category),
                        color = OwnPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = OwnPlayShapes.Small,
                            color = OwnPlayColors.Surface,
                        ) {
                            Text(
                                text = itemCount(category.categoryId).toString(),
                                color = OwnPlayColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                        Text("›", color = OwnPlayColors.TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryMovieGridContent(
    movies: List<LibraryMovieSummary>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenMovie: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
) {
    if (movies.isEmpty()) {
        Text(
            text = "No Movies are available in this provider category.",
            color = OwnPlayColors.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(movies, key = { it.movieId }) { movie ->
            LibraryMoviePosterCard(
                movie = movie,
                artworkLoader = artworkLoader,
                compact = compact,
                showProviderFlags = showProviderFlags,
                hideProviderPrefix = hideProviderPrefix,
                fillWidth = true,
                onOpen = { onOpenMovie(movie.movieId) },
                onFavorite = {
                    onToggleFavorite(
                        LibraryContentKind.MOVIE,
                        movie.movieId,
                        !movie.favorite,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibrarySeriesGridContent(
    series: List<LibrarySeriesSummary>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenSeries: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
) {
    if (series.isEmpty()) {
        Text(
            text = "No Series are available in this provider category.",
            color = OwnPlayColors.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        gridItems(series, key = { it.seriesId }) { item ->
            LibrarySeriesPosterCard(
                series = item,
                artworkLoader = artworkLoader,
                compact = compact,
                showProviderFlags = showProviderFlags,
                hideProviderPrefix = hideProviderPrefix,
                fillWidth = true,
                onOpen = { onOpenSeries(item.seriesId) },
                onFavorite = {
                    onToggleFavorite(
                        LibraryContentKind.SERIES,
                        item.seriesId,
                        !item.favorite,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibrarySearchContent(
    query: String,
    movies: List<LibraryMovieSummary>,
    series: List<LibrarySeriesSummary>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
) {
    if (query.isBlank()) {
        Text(
            text = "Search Movies and Series from the active source.",
            color = OwnPlayColors.TextMuted,
        )
        return
    }

    if (movies.isEmpty() && series.isEmpty()) {
        Text(
            text = "No Library items match your search.",
            color = OwnPlayColors.TextMuted,
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (movies.isNotEmpty()) {
            item {
                LibraryMovieShelf(
                    title = "Movies",
                    items = movies,
                    artworkLoader = artworkLoader,
                    compact = compact,
                    showProviderFlags = showProviderFlags,
                    hideProviderPrefix = hideProviderPrefix,
                    onOpenMovie = onOpenMovie,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
        if (series.isNotEmpty()) {
            item {
                LibrarySeriesShelf(
                    title = "Series",
                    items = series,
                    artworkLoader = artworkLoader,
                    compact = compact,
                    showProviderFlags = showProviderFlags,
                    hideProviderPrefix = hideProviderPrefix,
                    onOpenSeries = onOpenSeries,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun LibraryFavoritesContent(
    kind: LibraryFavoriteKind,
    movies: List<LibraryMovieSummary>,
    series: List<LibrarySeriesSummary>,
    movieGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    seriesGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
) {
    when (kind) {
        LibraryFavoriteKind.MOVIES -> {
            if (movies.isEmpty()) {
                Text(
                    text = "No favorite Movies yet.",
                    color = OwnPlayColors.TextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    state = movieGridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(movies, key = { it.movieId }) { movie ->
                        LibraryMoviePosterCard(
                            movie = movie,
                            artworkLoader = artworkLoader,
                            compact = compact,
                            showProviderFlags = showProviderFlags,
                            hideProviderPrefix = hideProviderPrefix,
                            fillWidth = true,
                            onOpen = { onOpenMovie(movie.movieId) },
                            onFavorite = {
                                onToggleFavorite(
                                    LibraryContentKind.MOVIE,
                                    movie.movieId,
                                    false,
                                )
                            },
                        )
                    }
                }
            }
        }

        LibraryFavoriteKind.SERIES -> {
            if (series.isEmpty()) {
                Text(
                    text = "No favorite Series yet.",
                    color = OwnPlayColors.TextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    state = seriesGridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(series, key = { it.seriesId }) { item ->
                        LibrarySeriesPosterCard(
                            series = item,
                            artworkLoader = artworkLoader,
                            compact = compact,
                            showProviderFlags = showProviderFlags,
                            hideProviderPrefix = hideProviderPrefix,
                            fillWidth = true,
                            onOpen = { onOpenSeries(item.seriesId) },
                            onFavorite = {
                                onToggleFavorite(
                                    LibraryContentKind.SERIES,
                                    item.seriesId,
                                    false,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryContinueWatchingAllContent(
    items: List<LibraryContinueWatchingItem>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpen: (LibraryContinueWatchingItem) -> Unit,
    onRemove: (LibraryContinueWatchingItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = "Nothing is currently in Continue Watching.",
            color = OwnPlayColors.TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = items.sortedByDescending(LibraryContinueWatchingItem::updatedAt),
            key = { item -> "continue-all:" + item.contentKind.name + ":" + item.contentId },
        ) { item ->
            Surface(
                color = OwnPlayColors.Surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(item) },
            ) {
                Row(
                    modifier = Modifier.padding(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.width(if (compact) 74.dp else 88.dp)) {
                        LibraryArtwork(
                            url = item.posterUrl,
                            loader = artworkLoader,
                            compact = compact,
                            expandPoster = true,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = libraryContinueWatchingTitle(
                                item = item,
                                showProviderFlags = showProviderFlags,
                                hideProviderPrefix = hideProviderPrefix,
                            ),
                            color = OwnPlayColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.seriesTitle?.takeIf(String::isNotBlank)?.let { seriesTitle ->
                            Text(
                                text = providerLibraryLabel(
                                    rawName = seriesTitle,
                                    showProviderFlags = showProviderFlags,
                                    hideProviderPrefix = hideProviderPrefix,
                                ),
                                color = OwnPlayColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                (item.positionMs.toFloat() / item.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { onRemove(item) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryContinueWatchingShelf(
    items: List<LibraryContinueWatchingItem>,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    title: String,
    onOpen: (LibraryContinueWatchingItem) -> Unit,
    onRemove: (LibraryContinueWatchingItem) -> Unit,
    onViewAll: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LibraryShelfHeader(title = title, onViewAll = onViewAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                items = items,
                key = { item -> "continue:" + item.contentKind.name + ":" + item.contentId },
            ) { item ->
                Surface(
                    color = OwnPlayColors.Surface,
                    modifier = Modifier
                        .widthIn(min = if (compact) 150.dp else 180.dp, max = 230.dp)
                        .clickable { onOpen(item) },
                ) {
                    Column(
                        modifier = Modifier.padding(if (compact) 8.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LibraryArtwork(
                            url = item.posterUrl,
                            loader = artworkLoader,
                            compact = compact,
                            expandPoster = true,
                        )
                        Text(
                            text = libraryContinueWatchingTitle(
                                item = item,
                                showProviderFlags = showProviderFlags,
                                hideProviderPrefix = hideProviderPrefix,
                            ),
                            color = OwnPlayColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.seriesTitle?.takeIf(String::isNotBlank)?.let { seriesTitle ->
                            Text(
                                text = providerLibraryLabel(
                                    rawName = seriesTitle,
                                    showProviderFlags = showProviderFlags,
                                    hideProviderPrefix = hideProviderPrefix,
                                ),
                                color = OwnPlayColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                (item.positionMs.toFloat() / item.durationMs.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { onRemove(item) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryMovieShelf(
    title: String,
    items: List<LibraryMovieSummary>,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenMovie: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
    onViewAll: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LibraryShelfHeader(title = title, onViewAll = onViewAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.movieId }) { movie ->
                LibraryMoviePosterCard(
                    movie = movie,
                    artworkLoader = artworkLoader,
                    compact = compact,
                    showProviderFlags = showProviderFlags,
                    hideProviderPrefix = hideProviderPrefix,
                    onOpen = { onOpenMovie(movie.movieId) },
                    onFavorite = {
                        onToggleFavorite(
                            LibraryContentKind.MOVIE,
                            movie.movieId,
                            !movie.favorite,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibrarySeriesShelf(
    title: String,
    items: List<LibrarySeriesSummary>,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    onOpenSeries: (String) -> Unit,
    onToggleFavorite: (LibraryContentKind, String, Boolean) -> Unit,
    onViewAll: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LibraryShelfHeader(title = title, onViewAll = onViewAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.seriesId }) { item ->
                LibrarySeriesPosterCard(
                    series = item,
                    artworkLoader = artworkLoader,
                    compact = compact,
                    showProviderFlags = showProviderFlags,
                    hideProviderPrefix = hideProviderPrefix,
                    onOpen = { onOpenSeries(item.seriesId) },
                    onFavorite = {
                        onToggleFavorite(
                            LibraryContentKind.SERIES,
                            item.seriesId,
                            !item.favorite,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryShelfHeader(
    title: String,
    onViewAll: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = OwnPlayColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        if (onViewAll != null) {
            TextButton(onClick = onViewAll) {
                Text("View all")
            }
        }
    }
}

private fun providerLibraryLabel(
    rawName: String,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
): String = ProviderCategoryDisplayPolicy.label(
    rawName = rawName,
    hideRegionPrefix = hideProviderPrefix,
    showFlag = showProviderFlags,
)

private fun libraryContinueWatchingTitle(
    item: LibraryContinueWatchingItem,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
): String = when (item.contentKind) {
    LibraryContentKind.MOVIE, LibraryContentKind.SERIES -> providerLibraryLabel(
        rawName = item.title,
        showProviderFlags = showProviderFlags,
        hideProviderPrefix = hideProviderPrefix,
    )
    LibraryContentKind.EPISODE -> item.title
}

@Composable
private fun LibraryMoviePosterCard(
    movie: LibraryMovieSummary,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    fillWidth: Boolean = false,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    LibraryPosterCard(
        title = providerLibraryLabel(
            rawName = movie.title,
            showProviderFlags = showProviderFlags,
            hideProviderPrefix = hideProviderPrefix,
        ),
        posterUrl = movie.posterUrl,
        rating = movie.rating,
        favorite = movie.favorite,
        artworkLoader = artworkLoader,
        compact = compact,
        fillWidth = fillWidth,
        onOpen = onOpen,
        onFavorite = onFavorite,
    )
}

@Composable
private fun LibrarySeriesPosterCard(
    series: LibrarySeriesSummary,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    showProviderFlags: Boolean,
    hideProviderPrefix: Boolean,
    fillWidth: Boolean = false,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    LibraryPosterCard(
        title = providerLibraryLabel(
            rawName = series.title,
            showProviderFlags = showProviderFlags,
            hideProviderPrefix = hideProviderPrefix,
        ),
        posterUrl = series.posterUrl,
        rating = series.rating,
        favorite = series.favorite,
        artworkLoader = artworkLoader,
        compact = compact,
        fillWidth = fillWidth,
        onOpen = onOpen,
        onFavorite = onFavorite,
    )
}

@Composable
private fun LibraryPosterCard(
    title: String,
    posterUrl: String?,
    rating: String?,
    favorite: Boolean,
    artworkLoader: LibraryArtworkLoader,
    compact: Boolean,
    fillWidth: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    val cardModifier = if (fillWidth) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.width(if (compact) 112.dp else 132.dp)
    }
    Surface(
        color = OwnPlayColors.Surface,
        modifier = cardModifier.clickable(onClick = onOpen),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 4.dp else 6.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LibraryArtwork(
                    url = posterUrl,
                    loader = artworkLoader,
                    compact = compact,
                    expandPoster = true,
                )
                Text(
                    text = if (favorite) "★" else "☆",
                    color = if (favorite) OwnPlayColors.Accent else OwnPlayColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .semantics {
                            contentDescription = if (favorite) "Remove favorite" else "Add favorite"
                        }
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(onClick = onFavorite)
                        .padding(8.dp),
                )
            }
            Text(
                text = title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            rating?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = "Rating " + it,
                    color = OwnPlayColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}
