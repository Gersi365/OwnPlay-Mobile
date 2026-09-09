package app.ownplay.player.ui.series

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.ownplay.player.ui.VNextMediaIconAction
import app.ownplay.player.ui.VNextMediaPill
import app.ownplay.player.ui.VNextMediaPrimaryAction
import app.ownplay.player.ui.VNextMediaSecondaryAction
import app.ownplay.player.ui.library.progressFraction
import app.ownplay.player.ui.theme.OwnPlayMediaLayout
import app.ownplay.player.ui.theme.OwnPlaySpacing
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
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (seasonNumber: Int, episodeId: String) -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
    onPlay: (SeriesEpisode) -> Unit,
    onDownload: (SeriesEpisode) -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onPlayFromBeginning: (SeriesEpisode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val primaryActionFocusRequester = remember(selected.seriesId) { FocusRequester() }
    val selectedEpisode = details
        ?.seasons
        ?.asSequence()
        ?.flatMap { it.episodes.asSequence() }
        ?.firstOrNull { it.episodeId == selectedEpisodeId }
    val activeSeason = details?.seasons?.firstOrNull { it.seasonNumber == selectedSeasonNumber }
        ?: details?.seasons?.firstOrNull()

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = OwnPlaySpacing.Lg, vertical = OwnPlaySpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
            ) {
                VNextMediaIconAction(
                    icon = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onClose,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedEpisode?.title ?: selected.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedEpisode?.let {
                            "Season ${it.seasonNumber} · Episode ${it.episodeNumber}"
                        } ?: "Series",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedEpisode == null) {
                    VNextMediaIconAction(
                        icon = if (selected.isFavorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Filled.FavoriteBorder
                        },
                        contentDescription = if (selected.isFavorite) {
                            "Remove favorite"
                        } else {
                            "Favorite"
                        },
                        selected = selected.isFavorite,
                        onClick = { onFavoriteChanged(!selected.isFavorite) },
                    )
                }
            }

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
            }
            if (error != null) {
                Text(
                    text = "Series details failed to load.",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            details?.let { loaded ->
                if (selectedEpisode != null) {
                    val download = downloads.episodeDownload(selectedEpisode.episodeId)
                    SeriesEpisodeDetailsPane(
                        episode = selectedEpisode,
                        download = download,
                        playFocusRequester = primaryActionFocusRequester,
                        onPlay = { onPlay(selectedEpisode) },
                        onDownload = { onDownload(selectedEpisode) },
                        onPauseDownload = onPauseDownload,
                        onResumeDownload = onResumeDownload,
                        onRetryDownload = onRetryDownload,
                        onRemoveDownload = onRemoveDownload,
                        onPlayFromBeginning = { onPlayFromBeginning(selectedEpisode) },
                    )
                } else {
                    SeriesInfoSummary(
                        selected = selected,
                        details = loaded,
                    )

                    latestResumeEpisode(loaded)?.let { resumeEpisode ->
                        VNextMediaPrimaryAction(
                            label =
                                "Resume S${resumeEpisode.seasonNumber.toString().padStart(2, '0')} " +
                                    "E${resumeEpisode.episodeNumber.toString().padStart(2, '0')}",
                            icon = Icons.Filled.PlayArrow,
                            onClick = { onPlay(resumeEpisode) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

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
                        SeriesSeasonSelector(
                            seasons = loaded.seasons,
                            selectedSeasonNumber = activeSeason?.seasonNumber,
                            onSeasonSelected = onSeasonSelected,
                        )
                        Text(
                            text = activeSeason?.let { season ->
                                "Episodes · Season ${season.seasonNumber}"
                            } ?: "Episodes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val episodes = activeSeason?.episodes.orEmpty()
                        if (episodes.isEmpty()) {
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
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
                            ) {
                                items(episodes, key = { it.episodeId }) { episode ->
                                    EpisodeCatalogRow(
                                        episode = episode,
                                        download = downloads.episodeDownload(episode.episodeId),
                                        onOpen = {
                                            onEpisodeSelected(episode.seasonNumber, episode.episodeId)
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
private fun SeriesSeasonSelector(
    seasons: List<SeriesSeason>,
    selectedSeasonNumber: Int?,
    onSeasonSelected: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        contentPadding = PaddingValues(end = OwnPlaySpacing.Md),
    ) {
        items(seasons, key = { it.seasonId }) { season ->
            VNextMediaPill(
                label = season.name?.takeIf(String::isNotBlank)
                    ?: "Season ${season.seasonNumber}",
                selected = season.seasonNumber == selectedSeasonNumber,
                onClick = { onSeasonSelected(season.seasonNumber) },
            )
        }
    }
}

@Composable
private fun EpisodeCatalogRow(
    episode: SeriesEpisode,
    download: OfflineDownload?,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Md),
        ) {
            Text(
                text = "E${episode.episodeNumber}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = episodeCatalogStatus(episode, download)
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (episode.resumeAvailable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "Open",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
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
    onPlayFromBeginning: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Md),
        verticalAlignment = Alignment.Top,
    ) {
        RemotePoster(
            url = episode.posterUrl,
            title = episode.title,
            modifier = Modifier
                .width(132.dp)
                .aspectRatio(OwnPlayMediaLayout.PosterAspectRatio),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs),
        ) {
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
            episode.positionMs?.takeIf { it > 0L }?.let { positionMs ->
                val progress = progressFraction(positionMs, episode.durationMs)
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    EpisodeDetailActions(
        episode = episode,
        download = download,
        playFocusRequester = playFocusRequester,
        onPlay = onPlay,
        onDownload = onDownload,
        onPauseDownload = onPauseDownload,
        onResumeDownload = onResumeDownload,
        onRetryDownload = onRetryDownload,
        onRemoveDownload = onRemoveDownload,
        onPlayFromBeginning = onPlayFromBeginning,
    )
}

@Composable
private fun EpisodeDetailActions(
    episode: SeriesEpisode,
    download: OfflineDownload?,
    playFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onPlayFromBeginning: () -> Unit,
) {
    val offlineCopyAvailable = download?.state == DownloadStates.COMPLETED
    val playLabel = when {
        offlineCopyAvailable && episode.resumeAvailable -> "Resume Offline"
        offlineCopyAvailable -> "Play Offline"
        episode.resumeAvailable -> "Resume"
        else -> "Play"
    }

    VNextMediaPrimaryAction(
        label = playLabel,
        icon = Icons.Filled.PlayArrow,
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(playFocusRequester),
    )

    if (episode.resumeAvailable) {
        VNextMediaSecondaryAction(
            label = "Play from beginning",
            onClick = onPlayFromBeginning,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (offlineCopyAvailable) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Sm),
        ) {
            Text(
                text = if (download?.savedToDownloads == true) {
                    "Downloaded · OwnPlay Downloads"
                } else {
                    "Downloaded · OwnPlay private storage"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val managedDownload = requireNotNull(download)
            VNextMediaIconAction(
                icon = Icons.Filled.Delete,
                contentDescription = "Remove episode download",
                onClick = { onRemoveDownload(managedDownload) },
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs),
        ) {
            val downloadLabel = when (download?.state) {
                DownloadStates.QUEUED,
                DownloadStates.DOWNLOADING,
                -> "Pause"
                DownloadStates.PAUSED -> "Resume"
                DownloadStates.FAILED -> "Retry"
                else -> "Download"
            }
            val downloadIcon = when (download?.state) {
                DownloadStates.QUEUED,
                DownloadStates.DOWNLOADING,
                -> Icons.Filled.Pause
                DownloadStates.PAUSED -> Icons.Filled.PlayArrow
                DownloadStates.FAILED -> Icons.Filled.Refresh
                else -> Icons.Filled.Download
            }
            VNextMediaSecondaryAction(
                label = downloadLabel,
                icon = downloadIcon,
                selected = download?.state == DownloadStates.QUEUED ||
                    download?.state == DownloadStates.DOWNLOADING ||
                    download?.state == DownloadStates.PAUSED,
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
                modifier = Modifier.weight(1f),
            )
            download?.let { managedDownload ->
                VNextMediaIconAction(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Remove episode download",
                    onClick = { onRemoveDownload(managedDownload) },
                )
            }
        }
    }

    if (
        download?.state == DownloadStates.DOWNLOADING ||
        download?.state == DownloadStates.QUEUED ||
        download?.state == DownloadStates.PAUSED
    ) {
        val fraction = download.progressFraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            seriesDownloadProgressLabel(download),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    download?.failureReason?.takeIf { download.state == DownloadStates.FAILED }?.let { failure ->
        Text(
            text = failure,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun List<OfflineDownload>.episodeDownload(episodeId: String): OfflineDownload? =
    firstOrNull { item ->
        item.mediaKind == DownloadMediaKinds.SERIES_EPISODE && item.contentId == episodeId
    }

internal fun latestResumeEpisode(details: SeriesDetails): SeriesEpisode? =
    details.seasons
        .asSequence()
        .flatMap { it.episodes.asSequence() }
        .filter(SeriesEpisode::resumeAvailable)
        .sortedWith(
            compareByDescending<SeriesEpisode> {
                it.progressUpdatedAtEpochMillis ?: Long.MIN_VALUE
            }
                .thenByDescending(SeriesEpisode::seasonNumber)
                .thenByDescending(SeriesEpisode::episodeNumber),
        )
        .firstOrNull()

private fun episodeCatalogStatus(
    episode: SeriesEpisode,
    download: OfflineDownload?,
): String? = when {
    episode.resumeAvailable -> "Resume available"
    episode.progressCompleted -> "Watched"
    download?.state == DownloadStates.COMPLETED -> "Available offline"
    download?.state == DownloadStates.DOWNLOADING -> "Downloading"
    download?.state == DownloadStates.QUEUED -> "Queued for download"
    download?.state == DownloadStates.PAUSED -> "Download paused"
    download?.state == DownloadStates.FAILED -> "Download failed"
    else -> null
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
