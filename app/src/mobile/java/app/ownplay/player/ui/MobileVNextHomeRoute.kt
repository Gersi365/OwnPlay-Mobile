package app.ownplay.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.ui.library.LibraryUnifiedContinueWatchingStrip
import app.ownplay.player.ui.library.unifiedContinueWatching
import app.ownplay.player.ui.theme.OwnPlayMediaLayout
import app.ownplay.player.ui.theme.OwnPlaySpacing
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import app.ownplay.player.vod.VodMovie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

private const val HOME_MEDIA_LIMIT = 18

private sealed interface HomeCatalogState {
    data object Loading : HomeCatalogState

    data class Ready(
        val vod: VodCatalog,
        val series: SeriesCatalog,
    ) : HomeCatalogState

    data object Error : HomeCatalogState
}

@Composable
internal fun MobileVNextHomeRoute(
    sourceId: String?,
    activeSourceName: String?,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sourceId == null) {
        HomeMessageSurface(
            title = "Your media starts here",
            body = "Connect a source to surface Continue Watching, Movies, Series and Live TV.",
            primaryActionLabel = "Open Settings",
            onPrimaryAction = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val vodRuntime = remember(context) { VodFeatureRuntime(context.applicationContext) }
    val seriesRuntime = remember(context) { SeriesFeatureRuntime(context.applicationContext) }
    DisposableEffect(vodRuntime, seriesRuntime) {
        onDispose {
            vodRuntime.close()
            seriesRuntime.close()
        }
    }

    val catalogState by produceState<HomeCatalogState>(
        initialValue = HomeCatalogState.Loading,
        sourceId,
        vodRuntime,
        seriesRuntime,
    ) {
        try {
            combine(
                vodRuntime.observeCatalog(sourceId),
                seriesRuntime.observeCatalog(sourceId),
            ) { vod, series ->
                HomeCatalogState.Ready(vod = vod, series = series)
            }.collect { state ->
                value = state
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = HomeCatalogState.Error
        }
    }

    when (val state = catalogState) {
        HomeCatalogState.Loading -> HomeLoadingSurface(modifier = modifier)
        HomeCatalogState.Error -> HomeMessageSurface(
            title = "Home could not load",
            body = "Your saved catalog could not be read right now. Library remains available.",
            primaryActionLabel = "Open Library",
            onPrimaryAction = onOpenLibrary,
            modifier = modifier,
        )
        is HomeCatalogState.Ready -> HomeReadyContent(
            sourceId = sourceId,
            activeSourceName = activeSourceName,
            vodCatalog = state.vod,
            seriesCatalog = state.series,
            onOpenMovieDetails = onOpenMovieDetails,
            onOpenSeriesDetails = onOpenSeriesDetails,
            onOpenLive = onOpenLive,
            onOpenLibrary = onOpenLibrary,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeReadyContent(
    sourceId: String,
    activeSourceName: String?,
    vodCatalog: VodCatalog,
    seriesCatalog: SeriesCatalog,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val continueWatching = remember(vodCatalog.continueWatching, seriesCatalog.continueWatching) {
        unifiedContinueWatching(
            movies = vodCatalog.continueWatching,
            episodes = seriesCatalog.continueWatching,
            limit = 12,
        )
    }
    val continuingMovieIds = remember(vodCatalog.continueWatching) {
        vodCatalog.continueWatching.mapTo(mutableSetOf()) { it.movieId }
    }
    val continuingSeriesIds = remember(seriesCatalog.continueWatching) {
        seriesCatalog.continueWatching.mapTo(mutableSetOf()) { it.seriesId }
    }
    val recentMovies = remember(vodCatalog.movies, continuingMovieIds) {
        homeRecentMovies(
            movies = vodCatalog.movies.filterNot { it.movieId in continuingMovieIds },
            limit = HOME_MEDIA_LIMIT,
        )
    }
    val recentSeries = remember(seriesCatalog.series, continuingSeriesIds) {
        homeRecentSeries(
            series = seriesCatalog.series.filterNot { it.seriesId in continuingSeriesIds },
            limit = HOME_MEDIA_LIMIT,
        )
    }
    val hasMedia = continueWatching.isNotEmpty() || recentMovies.isNotEmpty() || recentSeries.isNotEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = OwnPlaySpacing.Md,
            top = OwnPlaySpacing.Sm,
            end = OwnPlaySpacing.Md,
            bottom = OwnPlaySpacing.Xl,
        ),
        verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Lg),
    ) {
        item(key = "home-header") {
            Column(verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs)) {
                Text(
                    text = "Home",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                activeSourceName?.let { sourceName ->
                    Text(
                        text = sourceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "continue-watching") {
                LibraryUnifiedContinueWatchingStrip(
                    items = continueWatching,
                    onOpenMovie = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
                    onOpenSeries = { episode -> onOpenSeriesDetails(sourceId, episode.seriesId) },
                )
            }
        }

        if (recentMovies.isNotEmpty()) {
            item(key = "recent-movies") {
                HomePosterRow(
                    title = if (recentMovies.any { it.addedAtEpochSeconds != null }) {
                        "Recently Added Movies"
                    } else {
                        "Movies"
                    },
                    items = recentMovies,
                    stableKey = VodMovie::movieId,
                    posterUrl = VodMovie::posterUrl,
                    mediaTitle = VodMovie::name,
                    onOpen = { movie -> onOpenMovieDetails(sourceId, movie.movieId) },
                )
            }
        }

        if (recentSeries.isNotEmpty()) {
            item(key = "recent-series") {
                HomePosterRow(
                    title = if (recentSeries.any { it.lastModifiedEpochSeconds != null }) {
                        "Recently Updated Series"
                    } else {
                        "Series"
                    },
                    items = recentSeries,
                    stableKey = SeriesSummary::seriesId,
                    posterUrl = SeriesSummary::posterUrl,
                    mediaTitle = SeriesSummary::name,
                    onOpen = { series -> onOpenSeriesDetails(sourceId, series.seriesId) },
                )
            }
        }

        if (!hasMedia) {
            item(key = "home-empty") {
                HomeEmptyCatalog(
                    onOpenLive = onOpenLive,
                    onOpenLibrary = onOpenLibrary,
                )
            }
        }
    }
}

@Composable
private fun <T> HomePosterRow(
    title: String,
    items: List<T>,
    stableKey: (T) -> String,
    posterUrl: (T) -> String?,
    mediaTitle: (T) -> String,
    onOpen: (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = OwnPlaySpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        ) {
            items(items = items, key = stableKey) { item ->
                Column(
                    modifier = Modifier
                        .width(112.dp)
                        .clickable { onOpen(item) },
                    verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        RemotePoster(
                            url = posterUrl(item),
                            title = mediaTitle(item),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(OwnPlayMediaLayout.PosterAspectRatio),
                        )
                    }
                    Text(
                        text = mediaTitle(item),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLoadingSurface(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text(
                text = "Loading Home",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeMessageSurface(
    title: String,
    body: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(OwnPlaySpacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onPrimaryAction) {
                Text(primaryActionLabel)
            }
        }
    }
}

@Composable
private fun HomeEmptyCatalog(
    onOpenLive: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(OwnPlaySpacing.Md),
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs),
        ) {
            Text(
                text = "Nothing to resume yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Browse Library for Movies and Series, or open Live TV from the primary navigation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenLibrary) {
                Text("Open Library")
            }
            TextButton(onClick = onOpenLive) {
                Text("Open Live")
            }
        }
    }
}

internal fun homeRecentMovies(
    movies: List<VodMovie>,
    limit: Int = HOME_MEDIA_LIMIT,
): List<VodMovie> {
    if (limit <= 0) return emptyList()
    return movies
        .sortedWith(
            compareByDescending<VodMovie> { it.addedAtEpochSeconds ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
                .thenBy(VodMovie::movieId),
        )
        .take(limit)
}

internal fun homeRecentSeries(
    series: List<SeriesSummary>,
    limit: Int = HOME_MEDIA_LIMIT,
): List<SeriesSummary> {
    if (limit <= 0) return emptyList()
    return series
        .sortedWith(
            compareByDescending<SeriesSummary> {
                it.lastModifiedEpochSeconds ?: Long.MIN_VALUE
            }
                .thenBy { it.name.lowercase() }
                .thenBy(SeriesSummary::seriesId),
        )
        .take(limit)
}
