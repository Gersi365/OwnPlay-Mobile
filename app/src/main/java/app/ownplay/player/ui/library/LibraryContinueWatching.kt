package app.ownplay.player.ui.library

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.ui.vod.RemotePoster
import app.ownplay.player.vod.VodMovie
import kotlin.math.roundToInt

@Composable
internal fun LibraryMovieContinueWatchingStrip(
    movies: List<VodMovie>,
    onOpenMovie: (VodMovie) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (movies.isEmpty()) return
    LibraryContinueWatchingStrip(
        modifier = modifier,
        items = movies,
        key = { it.movieId },
        posterUrl = { it.posterUrl },
        title = { it.name },
        subtitle = { "Continue movie" },
        hideSubtitleOnMobile = true,
        positionMs = { it.positionMs },
        durationMs = { it.durationMs },
        onOpen = onOpenMovie,
    )
}

@Composable
internal fun LibrarySeriesContinueWatchingStrip(
    episodes: List<SeriesEpisode>,
    onOpenSeries: (SeriesEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (episodes.isEmpty()) return
    LibraryContinueWatchingStrip(
        modifier = modifier,
        items = episodes,
        key = { it.episodeId },
        posterUrl = { it.posterUrl },
        title = { it.seriesTitle },
        subtitle = { episode ->
            "S${episode.seasonNumber} · E${episode.episodeNumber} · ${episode.title}"
        },
        hideSubtitleOnMobile = false,
        positionMs = { it.positionMs },
        durationMs = { it.durationMs },
        onOpen = onOpenSeries,
    )
}

@Composable
private fun <T> LibraryContinueWatchingStrip(
    items: List<T>,
    key: (T) -> String,
    posterUrl: (T) -> String?,
    title: (T) -> String,
    subtitle: (T) -> String,
    hideSubtitleOnMobile: Boolean,
    positionMs: (T) -> Long?,
    durationMs: (T) -> Long?,
    onOpen: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val cardWidth = if (isTelevision) 172.dp else 138.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = if (isTelevision) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isTelevision) 10.dp else 8.dp),
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
                        MaterialTheme.colorScheme.surface
                    },
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RemotePoster(
                            url = posterUrl(item),
                            title = title(item),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        if (!isTelevision) {
                            ContinueWatchingProgressSlot(progress = progress)
                        }
                        Text(
                            text = title(item),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isTelevision || !hideSubtitleOnMobile) {
                            Text(
                                text = subtitle(item),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isTelevision) {
                            progress?.let { watched ->
                                LinearProgressIndicator(
                                    progress = { watched },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
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
