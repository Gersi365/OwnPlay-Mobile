package app.ownplay.player.ui.series

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.series.SeriesDetails
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.series.SeriesSeason
import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.source.SourceError
import app.ownplay.player.ui.vod.RemotePoster

@Composable
internal fun SeriesDetailsPane(
    selected: SeriesSummary,
    details: SeriesDetails?,
    loading: Boolean,
    error: SourceError?,
    selectedSeasonNumber: Int?,
    selectedEpisodeId: String?,
    downloads: List<OfflineDownload>,
    focusBackOnEntry: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (String) -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
    onPlay: (SeriesEpisode) -> Unit,
    onDownload: (SeriesEpisode) -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onClearProgress: (SeriesEpisode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val primaryActionFocusRequester = remember(selected.seriesId) { FocusRequester() }
    val selectedSeason = details?.seasons?.firstOrNull { it.seasonNumber == selectedSeasonNumber }
    val selectedEpisode = selectedSeason?.episodes?.firstOrNull { it.episodeId == selectedEpisodeId }

    LaunchedEffect(
        isTelevision,
        focusBackOnEntry,
        selected.seriesId,
        selectedSeasonNumber,
        selectedEpisodeId,
        details?.seasons?.size,
        selectedSeason?.episodes?.size,
    ) {
        if (!isTelevision) return@LaunchedEffect
        val primaryTargetAvailable = when {
            selectedEpisode != null -> true
            selectedSeason != null -> selectedSeason.episodes.isNotEmpty()
            details != null -> true
            else -> false
        }
        if (!primaryTargetAvailable) return@LaunchedEffect
        withFrameNanos { }
        primaryActionFocusRequester.requestFocus()
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            selectedEpisode != null -> selectedEpisode.title
                            selectedSeason != null -> selectedSeason.name ?: "Season ${selectedSeason.seasonNumber}"
                            else -> selected.name
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            selectedEpisode != null -> "Season ${selectedEpisode.seasonNumber} · Episode ${selectedEpisode.episodeNumber}"
                            selectedSeason != null -> "${selectedSeason.episodes.size} episode${if (selectedSeason.episodes.size == 1) "" else "s"}"
                            else -> "Series"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onClose) { Text("Back") }
            }

            if (selectedSeason == null && selectedEpisode == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onFavoriteChanged(!selected.isFavorite) },
                        modifier = if (details?.seasons?.isEmpty() == true) {
                            Modifier.focusRequester(primaryActionFocusRequester)
                        } else {
                            Modifier
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(if (selected.isFavorite) "Unfavorite" else "Favorite")
                    }
                }
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(12.dp))
            }
            error?.let {
                Text("Series details failed to load.", color = MaterialTheme.colorScheme.error)
            }

            details?.let { loaded ->
                when {
                    selectedEpisode != null -> {
                        SeriesEpisodeDetailsPane(
                            episode = selectedEpisode,
                            download = downloads.firstOrNull { item ->
                                item.mediaKind == DownloadMediaKinds.SERIES_EPISODE &&
                                    item.contentId == selectedEpisode.episodeId
                            },
                            playFocusRequester = primaryActionFocusRequester,
                            onPlay = { onPlay(selectedEpisode) },
                            onDownload = { onDownload(selectedEpisode) },
                            onPauseDownload = onPauseDownload,
                            onResumeDownload = onResumeDownload,
                            onRetryDownload = onRetryDownload,
                            onRemoveDownload = onRemoveDownload,
                            onClearProgress = { onClearProgress(selectedEpisode) },
                        )
                    }

                    selectedSeason != null -> {
                        SeriesSeasonHeader(
                            series = selected,
                            season = selectedSeason,
                        )
                        Text(
                            "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (selectedSeason.episodes.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No episodes available for this season.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val firstEpisodeId = selectedSeason.episodes.first().episodeId
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(selectedSeason.episodes, key = { it.episodeId }) { episode ->
                                    val download = downloads.firstOrNull { item ->
                                        item.mediaKind == DownloadMediaKinds.SERIES_EPISODE &&
                                            item.contentId == episode.episodeId
                                    }
                                    EpisodeRow(
                                        episode = episode,
                                        download = download,
                                        onOpen = { onEpisodeSelected(episode.episodeId) },
                                        playFocusRequester = if (episode.episodeId == firstEpisodeId) {
                                            primaryActionFocusRequester
                                        } else {
                                            null
                                        },
                                        onPlay = { onPlay(episode) },
                                        onDownload = { onDownload(episode) },
                                        onPauseDownload = onPauseDownload,
                                        onResumeDownload = onResumeDownload,
                                        onRetryDownload = onRetryDownload,
                                        onRemoveDownload = onRemoveDownload,
                                        onClearProgress = { onClearProgress(episode) },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        SeriesInfoSummary(
                            selected = selected,
                            details = loaded,
                        )
                        Text(
                            "Seasons",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (loaded.seasons.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "No seasons available.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val firstSeasonId = loaded.seasons.first().seasonId
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(loaded.seasons, key = { it.seasonId }) { season ->
                                    SeriesSeasonRow(
                                        series = selected,
                                        season = season,
                                        onClick = { onSeasonSelected(season.seasonNumber) },
                                        modifier = if (season.seasonId == firstSeasonId) {
                                            Modifier.focusRequester(primaryActionFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesSeasonRow(
    series: SeriesSummary,
    season: SeriesSeason,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemotePoster(
                url = season.posterUrl ?: series.posterUrl,
                title = season.name ?: "Season ${season.seasonNumber}",
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    season.name ?: "Season ${season.seasonNumber}",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${season.episodes.size} episode${if (season.episodes.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                season.airDate?.takeIf(String::isNotBlank)?.let { airDate ->
                    Text(
                        airDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Open",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SeriesSeasonHeader(
    series: SeriesSummary,
    season: SeriesSeason,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemotePoster(
                url = season.posterUrl ?: series.posterUrl,
                title = season.name ?: "Season ${season.seasonNumber}",
                modifier = Modifier
                    .width(88.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    season.name ?: "Season ${season.seasonNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${season.episodes.size} episode${if (season.episodes.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                season.airDate?.takeIf(String::isNotBlank)?.let { airDate ->
                    Text(
                        airDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesEpisodeDetailsPane(
    episode: SeriesEpisode,
    download: OfflineDownload?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onClearProgress: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RemotePoster(
                url = episode.posterUrl,
                title = episode.title,
                modifier = Modifier
                    .width(116.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Season ${episode.seasonNumber} · Episode ${episode.episodeNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                episode.durationSeconds?.takeIf { it > 0L }?.let { seconds ->
                    Text(
                        "${(seconds + 59L) / 60L} min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (episode.resumeAvailable) {
                    Text(
                        "Resume available",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (episode.progressCompleted) {
                    Text(
                        "Watched",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    EpisodeRow(
        episode = episode,
        download = download,
        onOpen = null,
        showHeader = false,
        playFocusRequester = playFocusRequester,
        onPlay = onPlay,
        onDownload = onDownload,
        onPauseDownload = onPauseDownload,
        onResumeDownload = onResumeDownload,
        onRetryDownload = onRetryDownload,
        onRemoveDownload = onRemoveDownload,
        onClearProgress = onClearProgress,
    )
}

@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    download: OfflineDownload?,
    onOpen: (() -> Unit)? = null,
    showHeader: Boolean = true,
    playFocusRequester: FocusRequester? = null,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onClearProgress: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED
    val rowModifier = if (onOpen == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    }

    Surface(
        modifier = rowModifier,
        shape = RoundedCornerShape(10.dp),
        color = if (showHeader) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = if (showHeader) 10.dp else 0.dp, vertical = 9.dp),
        ) {
            if (showHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "E${episode.episodeNumber} · ${episode.title}",
                            fontWeight = FontWeight.Medium,
                        )
                        episode.positionMs?.takeIf { it > 0L }?.let {
                            Text(
                                if (episode.resumeAvailable) "Resume available" else "Watched",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onOpen != null) {
                        Text(
                            "Details",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = if (showHeader) 7.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    modifier = playFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        when {
                            offlineCopyAvailable && episode.resumeAvailable -> "Resume Offline"
                            offlineCopyAvailable -> "Play Offline"
                            episode.resumeAvailable -> "Resume"
                            else -> "Play"
                        },
                    )
                }
                if (!isTelevision && !offlineCopyAvailable) {
                    Button(
                        onClick = {
                            when (download?.state) {
                                DownloadStates.QUEUED,
                                DownloadStates.DOWNLOADING,
                                -> onPauseDownload(download)
                                DownloadStates.PAUSED -> onResumeDownload(download)
                                DownloadStates.FAILED -> onRetryDownload(download)
                                null -> onDownload()
                                DownloadStates.COMPLETED -> Unit
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            when (download?.state) {
                                DownloadStates.QUEUED,
                                DownloadStates.DOWNLOADING,
                                -> "Pause"
                                DownloadStates.PAUSED -> "Resume"
                                DownloadStates.FAILED -> "Retry"
                                else -> "Download"
                            },
                        )
                    }
                }
                if (!isTelevision && download != null) {
                    IconButton(onClick = { onRemoveDownload(download) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove episode download")
                    }
                }
                if ((episode.positionMs ?: 0L) > 0L) {
                    TextButton(onClick = onClearProgress) { Text("Clear") }
                }
            }
            if (!isTelevision && offlineCopyAvailable) {
                Text(
                    text = "Downloaded · Offline copy",
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (
                !isTelevision &&
                (download?.state == DownloadStates.DOWNLOADING ||
                    download?.state == DownloadStates.QUEUED ||
                    download?.state == DownloadStates.PAUSED)
            ) {
                val fraction = download?.progressFraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                download?.let { managedDownload ->
                    Text(
                        seriesDownloadProgressLabel(managedDownload),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isTelevision) {
                download?.failureReason?.takeIf { download.state == DownloadStates.FAILED }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun seriesDownloadProgressLabel(download: OfflineDownload): String {
    val downloaded = seriesHumanBytes(download.bytesDownloaded)
    val totalBytes = download.totalBytes?.takeIf { it > 0L }
    val total = totalBytes?.let(::seriesHumanBytes)
    val prefix = when (download.state) {
        DownloadStates.PAUSED -> "Paused · "
        DownloadStates.QUEUED -> "Queued · "
        else -> ""
    }
    if (totalBytes == null || total == null) return "$prefix$downloaded"
    val percent = ((download.bytesDownloaded.toDouble() / totalBytes.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
    return "$prefix$downloaded / $total · $percent%"
}

private fun seriesHumanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
        safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
        safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
        else -> "$safe B"
    }
}
