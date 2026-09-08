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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodMovie
import kotlin.math.roundToInt

internal sealed interface LibraryContinueWatchingItem {
    val stableKey: String
    val progressUpdatedAtEpochMillis: Long?

    data class Movie(
        val movie: VodMovie,
    ) : LibraryContinueWatchingItem {
        override val stableKey: String = "movie:${movie.movieId}"
        override val progressUpdatedAtEpochMillis: Long? = movie.progressUpdatedAtEpochMillis
    }

    data class Episode(
        val episode: SeriesEpisode,
    ) : LibraryContinueWatchingItem {
        override val stableKey: String = "episode:${episode.episodeId}"
        override val progressUpdatedAtEpochMillis: Long? = episode.progressUpdatedAtEpochMillis
    }
}

internal fun unifiedContinueWatching(
    movies: List<VodMovie>,
    episodes: List<SeriesEpisode>,
    limit: Int = 24,
): List<LibraryContinueWatchingItem> {
    if (limit <= 0) return emptyList()
    return buildList<LibraryContinueWatchingItem> {
        movies.forEach { movie -> add(LibraryContinueWatchingItem.Movie(movie)) }
        episodes.forEach { episode -> add(LibraryContinueWatchingItem.Episode(episode)) }
    }
        .sortedWith(
            compareByDescending<LibraryContinueWatchingItem> {
                it.progressUpdatedAtEpochMillis ?: Long.MIN_VALUE
            }.thenBy(LibraryContinueWatchingItem::stableKey),
        )
        .take(limit)
}

@Composable
internal fun LibraryUnifiedContinueWatchingStrip(
    items: List<LibraryContinueWatchingItem>,
    onOpenMovie: (VodMovie) -> Unit,
    onOpenSeries: (SeriesEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    LibraryContinueWatchingStrip(
        modifier = modifier,
        items = items,
        key = LibraryContinueWatchingItem::stableKey,
        posterUrl = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> item.movie.posterUrl
                is LibraryContinueWatchingItem.Episode -> item.episode.posterUrl
            }
        },
        title = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> item.movie.name
                is LibraryContinueWatchingItem.Episode -> item.episode.seriesTitle
            }
        },
        subtitle = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> null
                is LibraryContinueWatchingItem.Episode -> with(item.episode) {
                    "S$seasonNumber · E$episodeNumber · $title"
                }
            }
        },
        positionMs = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> item.movie.positionMs
                is LibraryContinueWatchingItem.Episode -> item.episode.positionMs
            }
        },
        durationMs = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> item.movie.durationMs
                is LibraryContinueWatchingItem.Episode -> item.episode.durationMs
            }
        },
        onOpen = { item ->
            when (item) {
                is LibraryContinueWatchingItem.Movie -> onOpenMovie(item.movie)
                is LibraryContinueWatchingItem.Episode -> onOpenSeries(item.episode)
            }
        },
    )
}

@Composable
private fun <T> LibraryContinueWatchingStrip(
    items: List<T>,
    key: (T) -> String,
    posterUrl: (T) -> String?,
    title: (T) -> String,
    subtitle: (T) -> String?,
    positionMs: (T) -> Long?,
    durationMs: (T) -> Long?,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardWidth = 220.dp
    val posterWidth = 58.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = items, key = key) { item ->
                var focused by remember(key(item)) { mutableStateOf(false) }
                val progress = progressFraction(positionMs(item), durationMs(item))
                Surface(
                    modifier = Modifier
                        .width(cardWidth)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onOpen(item) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (focused) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                    },
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.width(posterWidth),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            RemotePoster(
                                url = posterUrl(item),
                                title = title(item),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f),
                            )
                            ContinueWatchingProgressSlot(progress = progress)
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = title(item),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            subtitle(item)?.let { secondaryText ->
                                Text(
                                    text = secondaryText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = continueWatchingResumeLabel(
                                    positionMs = positionMs(item),
                                    durationMs = durationMs(item),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingProgressSlot(progress: Float?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
    ) {
        progress?.let { watched ->
            LinearProgressIndicator(
                progress = { watched },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal fun progressFraction(positionMs: Long?, durationMs: Long?): Float? {
    val duration = durationMs?.takeIf { it > 0L } ?: return null
    val position = positionMs?.coerceAtLeast(0L) ?: return null
    return (position.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun continueWatchingResumeLabel(positionMs: Long?, durationMs: Long?): String {
    val progress = progressFraction(positionMs, durationMs) ?: return "Resume"
    return "Resume · ${(progress * 100f).roundToInt()}%"
}
