package app.ownplay.player.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadStates

internal data class LibraryOfflinePresentation(
    val verifiedOffline: Boolean,
    val badgeLabel: String?,
    val actionLabel: String?,
    val storageLabel: String?,
)

internal fun libraryOfflinePresentation(
    state: String?,
    savedToDownloads: Boolean,
): LibraryOfflinePresentation = if (state == DownloadStates.COMPLETED) {
    LibraryOfflinePresentation(
        verifiedOffline = true,
        badgeLabel = "OFFLINE",
        actionLabel = "Play Offline",
        storageLabel = if (savedToDownloads) {
            "Local file · Phone Downloads"
        } else {
            "Local file · OwnPlay private storage"
        },
    )
} else {
    LibraryOfflinePresentation(
        verifiedOffline = false,
        badgeLabel = null,
        actionLabel = null,
        storageLabel = null,
    )
}

internal fun OfflineDownload.libraryOfflinePresentation(): LibraryOfflinePresentation =
    libraryOfflinePresentation(
        state = state,
        savedToDownloads = savedToDownloads,
    )

internal fun librarySeriesOfflineLabel(offlineEpisodeCount: Int): String? =
    offlineEpisodeCount
        .takeIf { it > 0 }
        ?.let { count ->
            "OFFLINE · $count episode${if (count == 1) "" else "s"} local"
        }

@Composable
internal fun LibraryOfflineBadge(
    label: String = "OFFLINE",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
