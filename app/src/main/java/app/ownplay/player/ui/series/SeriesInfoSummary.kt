package app.ownplay.player.ui.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.series.SeriesDetails
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.ui.vod.RemotePoster

@Composable
internal fun SeriesInfoSummary(
    selected: SeriesSummary,
    details: SeriesDetails,
) {
    val episodeCount = details.seasons.sumOf { it.episodes.size }
    val metadata = buildList {
        details.rating?.let { add("★ ${"%.1f".format(it)}") }
        details.releaseDate?.takeIf(String::isNotBlank)?.let(::add)
        details.genre?.takeIf(String::isNotBlank)?.let(::add)
        if (details.seasons.isNotEmpty()) {
            add("${details.seasons.size} season${if (details.seasons.size == 1) "" else "s"}")
        }
        if (episodeCount > 0) {
            add("$episodeCount episode${if (episodeCount == 1) "" else "s"}")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemotePoster(
            url = details.posterUrl ?: selected.posterUrl,
            title = selected.name,
            modifier = Modifier
                .width(104.dp)
                .aspectRatio(2f / 3f),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            details.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
