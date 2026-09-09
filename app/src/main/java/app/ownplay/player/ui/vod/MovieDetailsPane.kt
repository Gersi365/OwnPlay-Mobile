package app.ownplay.player.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.source.SourceError
import app.ownplay.player.ui.VNextMediaIconAction
import app.ownplay.player.ui.VNextMediaPrimaryAction
import app.ownplay.player.ui.VNextMediaSecondaryAction
import app.ownplay.player.ui.theme.OwnPlayMediaLayout
import app.ownplay.player.ui.theme.OwnPlaySpacing
import app.ownplay.player.vod.VodMovie
import app.ownplay.player.vod.VodMovieDetails

@Composable
internal fun MovieDetailsPane(
    movie: VodMovie,
    details: VodMovieDetails?,
    loading: Boolean,
    error: SourceError?,
    download: OfflineDownload?,
    onDismiss: () -> Unit,
    onFavoriteChanged: (Boolean) -> Unit,
    onDownload: (VodMovie) -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
    onPlay: (VodMovie) -> Unit,
    onPlayFromBeginning: (VodMovie) -> Unit,
    modifier: Modifier,
) {
    val offlineCopyAvailable = download?.state == DownloadStates.COMPLETED
    val target = details?.movie ?: movie
    val title = details?.movie?.name ?: movie.name
    val playLabel = when {
        offlineCopyAvailable && movie.resumeAvailable -> "Resume Offline"
        offlineCopyAvailable -> "Play Offline"
        movie.resumeAvailable -> "Resume"
        else -> "Play"
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
                .padding(
                    horizontal = OwnPlaySpacing.Lg,
                    vertical = OwnPlaySpacing.Sm,
                ),
            verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VNextMediaIconAction(
                    icon = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onDismiss,
                )
                Spacer(Modifier.weight(1f))
                VNextMediaIconAction(
                    icon = if (movie.isFavorite) {
                        Icons.Filled.Favorite
                    } else {
                        Icons.Filled.FavoriteBorder
                    },
                    contentDescription = if (movie.isFavorite) "Remove favorite" else "Favorite",
                    selected = movie.isFavorite,
                    onClick = { onFavoriteChanged(!movie.isFavorite) },
                )
            }

            RemotePoster(
                url = details?.posterUrl ?: movie.posterUrl,
                title = title,
                modifier = Modifier
                    .width(188.dp)
                    .aspectRatio(OwnPlayMediaLayout.PosterAspectRatio)
                    .align(Alignment.CenterHorizontally),
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OwnPlaySpacing.Xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                val meta = details?.let { info ->
                    listOfNotNull(
                        info.releaseDate,
                        info.durationLabel,
                        info.genre,
                        info.rating?.let { "★ %.1f".format(it) },
                    ).joinToString("  ·  ")
                }.orEmpty()
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
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
                    text = "Detailed metadata unavailable. Playback remains available.",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            details?.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            VNextMediaPrimaryAction(
                label = playLabel,
                icon = Icons.Filled.PlayArrow,
                onClick = { onPlay(movie) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (movie.resumeAvailable) {
                VNextMediaSecondaryAction(
                    label = "Play from beginning",
                    onClick = { onPlayFromBeginning(movie) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            MovieDownloadContext(
                target = target,
                download = download,
                offlineCopyAvailable = offlineCopyAvailable,
                onDownload = onDownload,
                onPauseDownload = onPauseDownload,
                onResumeDownload = onResumeDownload,
                onRetryDownload = onRetryDownload,
                onRemoveDownload = onRemoveDownload,
            )
        }
    }
}

@Composable
private fun MovieDownloadContext(
    target: VodMovie,
    download: OfflineDownload?,
    offlineCopyAvailable: Boolean,
    onDownload: (VodMovie) -> Unit,
    onPauseDownload: (OfflineDownload) -> Unit,
    onResumeDownload: (OfflineDownload) -> Unit,
    onRetryDownload: (OfflineDownload) -> Unit,
    onRemoveDownload: (OfflineDownload) -> Unit,
) {
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val managedDownload = requireNotNull(download)
            VNextMediaIconAction(
                icon = Icons.Filled.Delete,
                contentDescription = "Remove download",
                onClick = { onRemoveDownload(managedDownload) },
            )
        }
        return
    }

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
                    null -> onDownload(target)
                    DownloadStates.COMPLETED -> Unit
                }
            },
            modifier = Modifier.weight(1f),
        )
        download?.let { managedDownload ->
            VNextMediaIconAction(
                icon = Icons.Filled.Delete,
                contentDescription = "Remove download",
                onClick = { onRemoveDownload(managedDownload) },
            )
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
                text = "Saving to OwnPlay Downloads",
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
