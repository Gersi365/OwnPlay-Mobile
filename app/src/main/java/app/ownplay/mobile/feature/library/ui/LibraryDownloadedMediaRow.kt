package app.ownplay.mobile.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController

@Composable
internal fun LibraryDownloadedMediaRow(
    item: DownloadItem,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    offlineResumeAvailable: Boolean = false,
    compact: Boolean = false,
) {
    val kindLabel = when (item.mediaKind) {
        DownloadMediaKind.MOVIE -> "Movie"
        DownloadMediaKind.EPISODE -> "Episode"
    }

    Surface(
        color = OwnPlayColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = kindLabel,
                color = OwnPlayColors.TextSecondary,
            )
            LibraryDownloadActions(
                request = DownloadRequest(item.sourceId, item.mediaKind, item.contentId, item.title),
                item = item,
                repository = repository,
                playbackSessionController = playbackSessionController,
                offlineResumeAvailable = offlineResumeAvailable,
            )
        }
    }
}
