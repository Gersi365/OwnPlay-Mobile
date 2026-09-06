package app.ownplay.player.ui.vod

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.source.SourceError
import app.ownplay.player.vod.VodMovie
import app.ownplay.player.vod.VodMovieDetails

@Composable
internal fun MovieDetailsPane(
    movie: VodMovie,
    details: VodMovieDetails?,
    loading: Boolean,
    error: SourceError?,
    download: OfflineDownload?,
    focusBackOnEntry: Boolean,
    onDismiss: () -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
    onDownload: (VodMovie) -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onClearProgress: () -> Unit,
    onPlay: (VodMovie) -> Unit,
    modifier: Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val detailPrimaryFocusRequester = remember(movie.movieId) { FocusRequester() }
    val offlineCopyAvailable = !isTelevision && download?.state == DownloadStates.COMPLETED

    LaunchedEffect(isTelevision, focusBackOnEntry, movie.movieId) {
        if (isTelevision) {
            withFrameNanos { }
            detailPrimaryFocusRequester.requestFocus()
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details?.movie?.name ?: movie.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Movie",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            RemotePoster(
                url = details?.posterUrl ?: movie.posterUrl,
                title = movie.name,
                modifier = Modifier
                    .width(184.dp)
                    .aspectRatio(2f / 3f)
                    .align(Alignment.CenterHorizontally),
            )

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.CenterHorizontally),
                    strokeWidth = 2.dp,
                )
            }
            if (error != null) {
                Text(
                    text = "Detailed metadata unavailable. Catalog playback remains available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            details?.let { info ->
                val meta = listOfNotNull(
                    info.releaseDate,
                    info.durationLabel,
                    info.genre,
                    info.country,
                    info.rating?.let { "★ %.1f".format(it) },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onPlay(movie) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(detailPrimaryFocusRequester),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            offlineCopyAvailable && movie.resumeAvailable -> "Resume Offline"
                            offlineCopyAvailable -> "Play Offline"
                            movie.resumeAvailable -> "Resume"
                            else -> "Play"
                        },
                    )
                }
                FilledTonalButton(
                    onClick = { onFavoriteChanged(!movie.isFavorite) },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        if (movie.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                    )
                }
            }

            if ((movie.positionMs ?: 0L) > 0L) {
                TextButton(onClick = onClearProgress) {
                    Text("Clear progress")
                }
            }

            if (!isTelevision) {
                val target = details?.movie ?: movie
                if (offlineCopyAvailable) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.DownloadDone, contentDescription = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (download?.savedToDownloads == true) {
                                        "Downloaded · Phone Downloads"
                                    } else {
                                        "Downloaded · Offline copy"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Play uses the local download first.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemoveDownload(requireNotNull(download)) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val downloadLabel = when (download?.state) {
                            DownloadStates.QUEUED -> "Pause"
                            DownloadStates.DOWNLOADING -> "Pause"
                            DownloadStates.PAUSED -> "Resume"
                            DownloadStates.FAILED -> "Retry"
                            else -> "Download"
                        }
                        FilledTonalButton(
                            onClick = {
                                when (download?.state) {
                                    DownloadStates.QUEUED,
                                    DownloadStates.DOWNLOADING,
                                    -> onPauseDownload(download)
                                    DownloadStates.PAUSED -> onResumeDownload(download)
                                    DownloadStates.FAILED -> onRetryDownload(download)
                                    null -> onDownload(target)
                                    DownloadStates.COMPLETED -> Unit
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = when (download?.state) {
                                    DownloadStates.QUEUED,
                                    DownloadStates.DOWNLOADING,
                                    -> Icons.Filled.Pause
                                    DownloadStates.PAUSED -> Icons.Filled.PlayArrow
                                    DownloadStates.FAILED -> Icons.Filled.Refresh
                                    else -> Icons.Filled.Download
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(downloadLabel)
                        }
                        download?.let { managedDownload ->
                            IconButton(onClick = { onRemoveDownload(managedDownload) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove download")
                            }
                        }
                    }
                }

                if (
                    download?.state == DownloadStates.DOWNLOADING ||
                    download?.state == DownloadStates.QUEUED ||
                    download?.state == DownloadStates.PAUSED
                ) {
                    val progress = download.progressFraction
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = movieDownloadProgressLabel(download),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (download.savedToDownloads) {
                        Text(
                            text = "Saving to phone Downloads",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (download?.state == DownloadStates.FAILED) {
                    Text(
                        text = download.failureReason ?: "Download failed. Retry when the source is available.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            details?.let { info ->
                val hasAbout =
                    !info.description.isNullOrBlank() ||
                        !info.director.isNullOrBlank() ||
                        !info.cast.isNullOrBlank()
                if (hasAbout) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "About",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            info.description?.takeIf(String::isNotBlank)?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            info.director?.takeIf(String::isNotBlank)?.let { director ->
                                Text(
                                    "Director · $director",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            info.cast?.takeIf(String::isNotBlank)?.let { cast ->
                                Text(
                                    "Cast · $cast",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun movieDownloadProgressLabel(download: OfflineDownload): String {
    val downloaded = movieHumanBytes(download.bytesDownloaded)
    val totalBytes = download.totalBytes?.takeIf { it > 0L }
    val total = totalBytes?.let(::movieHumanBytes)
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

private fun movieHumanBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
        safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
        safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
        else -> "$safe B"
    }
}
