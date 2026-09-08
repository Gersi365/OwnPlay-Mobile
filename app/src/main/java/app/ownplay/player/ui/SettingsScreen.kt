package app.ownplay.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.source.SourceSyncState

internal enum class SettingsDestination {
    INTERFACE,
    CONTENT,
    DOWNLOADS,
    ABOUT,
    LIVE_MANAGEMENT,
    PLAYLISTS,
}

@Composable
internal fun SettingsScreen(
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    syncState: SourceSyncState,
    activeSourceName: String?,
    hasActivePlayback: Boolean,
    onOpenLive: () -> Unit,
    onOpenSourceInLive: (String) -> Unit,
    onStopPlayback: () -> Unit,
) {
    var destination by remember { mutableStateOf(SettingsDestination.CONTENT) }
    val readySummaries = summaries.filter { summary -> summary.enabled }

    val nestedDestinationBackEnabled =
        destination == SettingsDestination.LIVE_MANAGEMENT ||
            destination == SettingsDestination.PLAYLISTS ||
            destination == SettingsDestination.DOWNLOADS
    BackHandler(enabled = nestedDestinationBackEnabled) {
        destination = SettingsDestination.CONTENT
    }

    when (destination) {
        SettingsDestination.LIVE_MANAGEMENT -> {
            LiveManagementScreen(
                runtime = runtime,
                summaries = readySummaries,
                onBack = { destination = SettingsDestination.CONTENT },
                focusBackOnEntry = true,
            )
            return
        }
        SettingsDestination.PLAYLISTS -> {
            PlaylistManagementSubscreen(
                runtime = runtime,
                summaries = summaries,
                syncState = syncState,
                onBack = { destination = SettingsDestination.CONTENT },
                onOpenInLive = onOpenSourceInLive,
                focusBackOnEntry = true,
            )
            return
        }
        SettingsDestination.DOWNLOADS -> {
            DownloadsSettingsScreen(
                onBack = { destination = SettingsDestination.CONTENT },
                focusBackOnEntry = true,
            )
            return
        }
        else -> Unit
    }

    PortraitSettingsMenu(
        summaries = summaries,
        onOpenLiveManagement = { destination = SettingsDestination.LIVE_MANAGEMENT },
        onOpenPlaylists = { destination = SettingsDestination.PLAYLISTS },
        onOpenDownloads = { destination = SettingsDestination.DOWNLOADS },
    )
}
