package app.ownplay.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.series.SeriesCatalog
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.ui.library.LibraryMovieContinueWatchingStrip
import app.ownplay.player.ui.library.LibrarySeriesContinueWatchingStrip
import app.ownplay.player.vod.VodCatalog
import app.ownplay.player.vod.VodFeatureRuntime
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun MobileHomeScreen(
    sourceId: String?,
    sourceKind: String?,
    onOpenSettings: () -> Unit,
    onOpenMovieDetails: (sourceId: String, movieId: String) -> Unit,
    onOpenSeriesDetails: (sourceId: String, seriesId: String) -> Unit,
    modifier: Modifier = Modifier,
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

    LaunchedEffect(sourceId, sourceKind, vodRuntime, seriesRuntime) {
        val resolvedSourceId = sourceId ?: return@LaunchedEffect
        if (sourceKind != SourceKinds.XTREAM) return@LaunchedEffect
        vodRuntime.refresh(resolvedSourceId)
        seriesRuntime.refresh(resolvedSourceId)
    }

    val hasContinueWatching =
        vodCatalog.continueWatching.isNotEmpty() || seriesCatalog.continueWatching.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Resume what you were watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            sourceId == null -> HomeEmptyState(
                title = "No playlist configured",
                detail = "Add an Xtream or M3U playlist in Settings to start building your Home.",
                actionLabel = "Open Settings",
                onAction = onOpenSettings,
            )

            !hasContinueWatching -> HomeEmptyState(
                title = "Nothing to resume yet",
                detail = "Partially watched Movies and Series episodes will appear here.",
            )

            else -> {
                LibraryMovieContinueWatchingStrip(
                    movies = vodCatalog.continueWatching,
                    onOpenMovie = { movie ->
                        onOpenMovieDetails(sourceId, movie.movieId)
                    },
                    heading = "Continue Watching · Movies",
                )
                LibrarySeriesContinueWatchingStrip(
                    episodes = seriesCatalog.continueWatching,
                    onOpenSeries = { episode ->
                        onOpenSeriesDetails(sourceId, episode.seriesId)
                    },
                    heading = "Continue Watching · Series",
                )
            }
        }
    }
}

@Composable
private fun HomeEmptyState(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
