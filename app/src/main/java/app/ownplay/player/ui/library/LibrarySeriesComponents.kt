package app.ownplay.player.ui.library

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.series.SeriesDetails
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.series.SeriesFeatureRuntime
import app.ownplay.player.series.SeriesSeason
import app.ownplay.player.source.SourceResult
import app.ownplay.player.ui.vod.RemotePoster
import java.util.Locale

@Composable
internal fun LibrarySeriesCard(
    group: LibrarySeriesGroup,
    onOpenOfflineSeries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completed = group.offlineEpisodeCount
    val active = group.episodes.count {
        it.state == DownloadStates.DOWNLOADING || it.state == DownloadStates.QUEUED
    }
    val paused = group.episodes.count { it.state == DownloadStates.PAUSED }
    val failed = group.episodes.count { it.state == DownloadStates.FAILED }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenOfflineSeries),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box {
                RemotePoster(
                    url = group.posterUrl,
                    title = group.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = group.episodeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Text(
                text = group.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${group.episodeCount} in Library")
                    if (group.seasonCount > 0) {
                        append(" · ${group.seasonCount} season")
                        if (group.seasonCount != 1) append("s")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (completed > 0) {
                LibraryOfflineBadge()
            }
            Text(
                text = seriesTransferSummary(
                    completed = completed,
                    active = active,
                    paused = paused,
                    failed = failed,
                    totalBytes = group.totalBytesDownloaded,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun LibrarySeriesDetailScreen(
    group: LibrarySeriesGroup,
    playbackError: String?,
    returnFocusEpisodeId: String? = null,
    returnFocusGeneration: Int = 0,
    onBack: () -> Unit,
    onOpenFullSeries: (() -> Unit)?,
    onDownloadEpisode: (SeriesEpisode) -> Unit,
    onPlay: (OfflineDownload) -> Unit,
    onPause: (OfflineDownload) -> Unit,
    onResume: (OfflineDownload) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onRemove: (OfflineDownload) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val seriesRuntime = remember(context) {
        SeriesFeatureRuntime(context.applicationContext)
    }
    DisposableEffect(seriesRuntime) {
        onDispose { seriesRuntime.close() }
    }

    val seriesId = group.seriesId
    val detailBackFocusRequester = remember(group.key) { FocusRequester() }
    val episodeActionFocusRequester = remember(group.key) { FocusRequester() }
    var showAll by remember(group.key) { mutableStateOf(false) }
    var fullDetails by remember(group.key) { mutableStateOf<SeriesDetails?>(null) }
    var catalogLoading by remember(group.key) { mutableStateOf(seriesId != null) }
    var catalogLoadFailed by remember(group.key) { mutableStateOf(false) }
    var retryNonce by remember(group.key) { mutableIntStateOf(0) }
    var selectedSeasonNumber by remember(group.key) { mutableStateOf<Int?>(null) }
    var selectedEpisodeId by remember(group.key) { mutableStateOf<String?>(null) }

    LaunchedEffect(isTelevision, group.key, selectedSeasonNumber, selectedEpisodeId) {
        if (isTelevision) {
            detailBackFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(seriesId, retryNonce) {
        if (seriesId == null || fullDetails != null) {
            catalogLoading = false
            return@LaunchedEffect
        }
        catalogLoading = true
        catalogLoadFailed = false
        when (val result = seriesRuntime.details(group.key.sourceId, seriesId)) {
            is SourceResult.Success -> fullDetails = result.value
            is SourceResult.Failure -> catalogLoadFailed = true
        }
        catalogLoading = false
    }

    val managedByEpisodeId = remember(group.episodes) {
        group.episodes.associateBy { it.contentId }
    }
    val catalogSeasons = fullDetails?.seasons.orEmpty()
    val seasonCards = remember(catalogSeasons, group.episodes, showAll) {
        if (catalogSeasons.isNotEmpty()) {
            catalogSeasons
                .map { season ->
                    val managedCount = season.episodes.count { episode ->
                        managedByEpisodeId.containsKey(episode.episodeId)
                    }
                    LibrarySeasonCardModel(
                        seasonNumber = season.seasonNumber,
                        title = season.name?.trim()?.takeIf(String::isNotBlank)
                            ?: "Season ${season.seasonNumber}",
                        posterUrl = season.posterUrl ?: group.posterUrl,
                        managedCount = managedCount,
                        totalCount = season.episodes.size,
                        catalogSeason = season,
                    )
                }
                .filter { showAll || it.managedCount > 0 }
        } else {
            group.seasonNumbers.map { seasonNumber ->
                val managedCount = group.episodes.count { it.seasonNumber == seasonNumber }
                LibrarySeasonCardModel(
                    seasonNumber = seasonNumber,
                    title = "Season $seasonNumber",
                    posterUrl = group.posterUrl,
                    managedCount = managedCount,
                    totalCount = managedCount,
                    catalogSeason = null,
                )
            }
        }
    }

    LaunchedEffect(showAll, seasonCards) {
        val selected = selectedSeasonNumber
        if (selected != null && seasonCards.none { it.seasonNumber == selected }) {
            selectedSeasonNumber = null
            selectedEpisodeId = null
        }
    }

    val selectedSeason = seasonCards.firstOrNull { it.seasonNumber == selectedSeasonNumber }
    val seasonManagedEpisodes = remember(group.episodes, selectedSeasonNumber) {
        selectedSeasonNumber?.let { seasonNumber ->
            group.episodes.filter { it.seasonNumber == seasonNumber }
        }.orEmpty()
    }
    val catalogEpisodes = selectedSeason?.catalogSeason?.episodes.orEmpty()
    val visibleEpisodes = remember(catalogEpisodes, seasonManagedEpisodes, showAll) {
        if (catalogEpisodes.isNotEmpty()) {
            catalogEpisodes
                .sortedWith(
                    compareBy<SeriesEpisode>(
                        { it.episodeNumber },
                        { it.title.lowercase(Locale.ROOT) },
                    ),
                )
                .filter { episode -> showAll || managedByEpisodeId.containsKey(episode.episodeId) }
                .map { episode ->
                    LibraryEpisodeCardModel(
                        episodeId = episode.episodeId,
                        title = episode.title,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        posterUrl = episode.posterUrl ?: selectedSeason?.posterUrl ?: group.posterUrl,
                        catalogEpisode = episode,
                        download = managedByEpisodeId[episode.episodeId],
                    )
                }
        } else {
            seasonManagedEpisodes.map { download ->
                LibraryEpisodeCardModel(
                    episodeId = download.contentId,
                    title = download.title,
                    seasonNumber = download.seasonNumber ?: selectedSeasonNumber ?: 0,
                    episodeNumber = download.episodeNumber ?: 0,
                    posterUrl = download.posterUrl ?: selectedSeason?.posterUrl ?: group.posterUrl,
                    catalogEpisode = null,
                    download = download,
                )
            }
        }
    }

    LaunchedEffect(selectedSeasonNumber, visibleEpisodes) {
        val selected = selectedEpisodeId
        if (selected != null && visibleEpisodes.none { it.episodeId == selected }) {
            selectedEpisodeId = null
        }
    }

    val selectedEpisode = visibleEpisodes.firstOrNull { it.episodeId == selectedEpisodeId }
    val totalCatalogEpisodes = fullDetails?.seasons?.sumOf { it.episodes.size }

    LaunchedEffect(
        isTelevision,
        returnFocusEpisodeId,
        returnFocusGeneration,
        selectedEpisodeId,
    ) {
        if (
            isTelevision &&
            returnFocusGeneration > 0 &&
            returnFocusEpisodeId != null &&
            selectedEpisodeId == returnFocusEpisodeId
        ) {
            withFrameNanos { }
            episodeActionFocusRequester.requestFocus()
        }
    }

    fun navigateBack() {
        when {
            selectedEpisodeId != null -> selectedEpisodeId = null
            selectedSeasonNumber != null -> selectedSeasonNumber = null
            else -> onBack()
        }
    }

    BackHandler(onBack = ::navigateBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = ::navigateBack,
                modifier = Modifier.focusRequester(detailBackFocusRequester),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        selectedEpisode != null -> selectedEpisode.title
                        selectedSeason != null -> selectedSeason.title
                        else -> group.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        selectedEpisode != null -> if (selectedEpisode.download != null) "Managed episode" else "Series episode"
                        selectedSeason != null -> if (selectedSeason.managedCount > 0) "Managed season" else "Series season"
                        else -> when (group.availability) {
                            LibrarySeriesAvailability.OFFLINE -> "Offline series"
                            LibrarySeriesAvailability.MANAGED -> "Series downloads"
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            selectedEpisode != null -> OfflineEpisodeHero(
                model = selectedEpisode,
                primaryActionFocusRequester = episodeActionFocusRequester
                    .takeIf {
                        isTelevision &&
                            returnFocusGeneration > 0 &&
                            selectedEpisode.episodeId == returnFocusEpisodeId
                    },
                onOpenFullSeries = onOpenFullSeries,
                onDownload = selectedEpisode.catalogEpisode?.let { episode ->
                    { onDownloadEpisode(episode) }
                },
                onPlay = selectedEpisode.download?.let { download -> { onPlay(download) } },
                onPause = selectedEpisode.download?.let { download -> { onPause(download) } },
                onResume = selectedEpisode.download?.let { download -> { onResume(download) } },
                onRetry = selectedEpisode.download?.let { download -> { onRetry(download) } },
                onRemove = selectedEpisode.download?.let { download -> { onRemove(download) } },
            )

            selectedSeason != null -> OfflineSeasonHero(
                model = selectedSeason,
                seriesTitle = group.title,
                onOpenFullSeries = onOpenFullSeries,
            )

            else -> OfflineSeriesHero(
                group = group,
                totalCatalogEpisodes = totalCatalogEpisodes,
                showAll = showAll,
                canShowAll = seriesId != null,
                onToggleShowAll = {
                    showAll = !showAll
                    selectedEpisodeId = null
                },
                onOpenFullSeries = onOpenFullSeries,
            )
        }

        if (catalogLoading && selectedSeasonNumber == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = "Loading the complete series from your playlist…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (catalogLoadFailed) {
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
                        text = if (group.hasOfflineEpisodes) {
                            "The full series could not be loaded. Your offline downloads remain available."
                        } else {
                            "The full series could not be loaded. Your Library downloads remain available."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            catalogLoadFailed = false
                            retryNonce += 1
                        },
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        playbackError?.let { message ->
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
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (selectedSeasonNumber == null) {
            Text(
                text = "Seasons",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (seasonCards.isEmpty() && !catalogLoading) {
                EmptyOfflineSection("No seasons available")
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(seasonCards, key = { it.seasonNumber }) { season ->
                        OfflineSeasonCard(
                            model = season,
                            onClick = {
                                selectedSeasonNumber = season.seasonNumber
                                selectedEpisodeId = null
                            },
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (seriesId != null) {
                    TextButton(
                        onClick = {
                            showAll = !showAll
                            selectedEpisodeId = null
                        },
                    ) {
                        Text(if (showAll) "Managed only" else "Show all")
                    }
                }
            }

            if (visibleEpisodes.isEmpty()) {
                EmptyOfflineSection(
                    if (showAll) "No episodes available" else "No managed episodes in this season",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleEpisodes, key = { it.episodeId }) { episode ->
                        OfflineEpisodeCard(
                            model = episode,
                            selected = selectedEpisodeId == episode.episodeId,
                            onClick = { selectedEpisodeId = episode.episodeId },
                            onDownload = episode.catalogEpisode
                                ?.takeIf { episode.download == null }
                                ?.let { catalogEpisode ->
                                    { onDownloadEpisode(catalogEpisode) }
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineSeriesHero(
    group: LibrarySeriesGroup,
    totalCatalogEpisodes: Int?,
    showAll: Boolean,
    canShowAll: Boolean,
    onToggleShowAll: () -> Unit,
    onOpenFullSeries: (() -> Unit)?,
) {
    val completed = group.offlineEpisodeCount

    OfflineHeroShell(
        posterUrl = group.posterUrl,
        title = group.title,
        eyebrow = when (group.availability) {
            LibrarySeriesAvailability.OFFLINE -> "OFFLINE SERIES"
            LibrarySeriesAvailability.MANAGED -> "SERIES DOWNLOADS"
        },
        meta = buildString {
            append("$completed downloaded")
            if (group.episodeCount != completed) append(" · ${group.episodeCount} managed")
            if (totalCatalogEpisodes != null) append(" · $totalCatalogEpisodes total")
        },
        secondary = "${group.seasonCount} managed season${if (group.seasonCount == 1) "" else "s"} · ${humanBytes(group.totalBytesDownloaded)}",
    ) {
        if (onOpenFullSeries != null) {
            OutlinedButton(
                onClick = onOpenFullSeries,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("View full series")
            }
        }
        if (canShowAll) {
            TextButton(onClick = onToggleShowAll) {
                Text(if (showAll) "Managed only" else "Show all")
            }
        }
    }
}

@Composable
private fun OfflineSeasonHero(
    model: LibrarySeasonCardModel,
    seriesTitle: String,
    onOpenFullSeries: (() -> Unit)?,
) {
    OfflineHeroShell(
        posterUrl = model.posterUrl,
        title = model.title,
        eyebrow = seriesTitle.uppercase(Locale.ROOT),
        meta = "${model.managedCount}/${model.totalCount} in Library",
        secondary = if (model.managedCount == 0) {
            "No managed episodes yet"
        } else {
            "${model.managedCount} managed episode${if (model.managedCount == 1) "" else "s"}"
        },
    ) {
        if (onOpenFullSeries != null) {
            OutlinedButton(
                onClick = onOpenFullSeries,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("View full series")
            }
        }
    }
}

@Composable
private fun OfflineEpisodeHero(
    model: LibraryEpisodeCardModel,
    primaryActionFocusRequester: FocusRequester?,
    onOpenFullSeries: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val download = model.download
    val primaryActionModifier = primaryActionFocusRequester
        ?.let { requester -> Modifier.focusRequester(requester) }
        ?: Modifier
    OfflineHeroShell(
        posterUrl = model.posterUrl,
        title = model.title,
        eyebrow = "SEASON ${model.seasonNumber} · EPISODE ${model.episodeNumber}",
        meta = download?.let(::episodeDownloadStatus) ?: "Not downloaded",
        secondary = download?.let { episodeStorageLabel(it) }
            ?: "Available from your playlist when online",
        dimPoster = download == null,
    ) {
        when (download?.state) {
            DownloadStates.COMPLETED -> Button(onClick = requireNotNull(onPlay), modifier = primaryActionModifier) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Play Offline")
            }

            DownloadStates.DOWNLOADING,
            DownloadStates.QUEUED,
            -> FilledTonalButton(onClick = requireNotNull(onPause), modifier = primaryActionModifier) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pause")
            }

            DownloadStates.PAUSED -> FilledTonalButton(onClick = requireNotNull(onResume), modifier = primaryActionModifier) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Resume")
            }

            DownloadStates.FAILED -> FilledTonalButton(onClick = requireNotNull(onRetry), modifier = primaryActionModifier) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Retry")
            }

            null -> if (onDownload != null) {
                FilledTonalButton(onClick = onDownload, modifier = primaryActionModifier) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            }
        }

        if (onOpenFullSeries != null) {
            TextButton(onClick = onOpenFullSeries) {
                Text("View full series")
            }
        }
        if (download != null && onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove episode download")
            }
        }
    }

    if (download != null && download.state in setOf(
            DownloadStates.DOWNLOADING,
            DownloadStates.QUEUED,
            DownloadStates.PAUSED,
        )
    ) {
        val progress = download.progressFraction
        Spacer(Modifier.height(6.dp))
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OfflineHeroShell(
    posterUrl: String?,
    title: String,
    eyebrow: String,
    meta: String,
    secondary: String,
    dimPoster: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemotePoster(
                url = posterUrl,
                title = title,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(2f / 3f)
                    .then(if (dimPoster) Modifier.alpha(0.5f) else Modifier),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun OfflineSeasonCard(
    model: LibrarySeasonCardModel,
    onClick: () -> Unit,
) {
    val dimmed = model.managedCount == 0
    Surface(
        modifier = Modifier
            .width(132.dp)
            .clickable(onClick = onClick)
            .then(if (dimmed) Modifier.alpha(0.55f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = if (dimmed) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            RemotePoster(
                url = model.posterUrl,
                title = model.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            Text(
                text = model.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${model.managedCount}/${model.totalCount} in Library",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineEpisodeCard(
    model: LibraryEpisodeCardModel,
    selected: Boolean,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val missing = model.download == null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (missing) Modifier.alpha(0.62f) else Modifier),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = if (selected) 4.dp else if (missing) 0.dp else 1.dp,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RemotePoster(
                url = model.posterUrl,
                title = model.title,
                modifier = Modifier
                    .width(68.dp)
                    .aspectRatio(2f / 3f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Season ${model.seasonNumber} · Episode ${model.episodeNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = model.download?.let(::episodeDownloadStatus) ?: "Not downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (model.download?.state == DownloadStates.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else if (missing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (missing && onDownload != null) {
                FilledTonalButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            }
        }
    }
}

@Composable
private fun EmptyOfflineSection(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class LibrarySeasonCardModel(
    val seasonNumber: Int,
    val title: String,
    val posterUrl: String?,
    val managedCount: Int,
    val totalCount: Int,
    val catalogSeason: SeriesSeason?,
)

private data class LibraryEpisodeCardModel(
    val episodeId: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val posterUrl: String?,
    val catalogEpisode: SeriesEpisode?,
    val download: OfflineDownload?,
)

private fun episodeDownloadStatus(download: OfflineDownload): String = when (download.state) {
    DownloadStates.COMPLETED -> "OFFLINE · Downloaded · ${humanBytes(download.bytesDownloaded)}"
    DownloadStates.DOWNLOADING -> downloadProgressLabel(download)
    DownloadStates.QUEUED -> "Queued"
    DownloadStates.PAUSED -> "Paused · ${humanBytes(download.bytesDownloaded)}"
    DownloadStates.FAILED -> download.failureReason ?: "Download failed"
    else -> "Managed by OwnPlay"
}

private fun episodeStorageLabel(download: OfflineDownload): String = when {
    download.state != DownloadStates.COMPLETED -> "Managed by OwnPlay"
    download.savedToDownloads -> "Local file · Phone Downloads"
    else -> "Local file · OwnPlay private storage"
}

private fun seriesTransferSummary(
    completed: Int,
    active: Int,
    paused: Int,
    failed: Int,
    totalBytes: Long,
): String {
    val parts = buildList {
        if (completed > 0) add("$completed downloaded")
        if (active > 0) add("$active downloading")
        if (paused > 0) add("$paused paused")
        if (failed > 0) add("$failed failed")
    }
    val state = parts.joinToString(" · ").ifBlank { "No completed episodes" }
    return "$state · ${humanBytes(totalBytes)}"
}

private fun downloadProgressLabel(download: OfflineDownload): String {
    val downloaded = humanBytes(download.bytesDownloaded)
    val total = download.totalBytes?.takeIf { it > 0L }?.let(::humanBytes)
    val state = when (download.state) {
        DownloadStates.PAUSED -> "Paused"
        DownloadStates.QUEUED -> "Queued"
        else -> "Downloading"
    }
    return if (total == null) "$state · $downloaded" else "$state · $downloaded / $total"
}

private fun humanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
        safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
        safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
        else -> "$safe B"
    }
}
