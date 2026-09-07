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
    HOME,
    SOURCES,
    DOWNLOADS,
    LIVE_MANAGEMENT,
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
    var destination by remember { mutableStateOf(SettingsDestination.HOME) }
    val readySummaries = summaries.filter { summary -> summary.enabled }

    BackHandler(enabled = destination != SettingsDestination.HOME) {
        destination = SettingsDestination.HOME
    }

    when (destination) {
        SettingsDestination.LIVE_MANAGEMENT -> {
            LiveManagementScreen(
                runtime = runtime,
                summaries = readySummaries,
                onBack = { destination = SettingsDestination.HOME },
            )
            return
        }
        SettingsDestination.SOURCES -> {
            PlaylistManagementSubscreen(
                runtime = runtime,
                summaries = summaries,
                syncState = syncState,
                onBack = { destination = SettingsDestination.HOME },
                onOpenInLive = onOpenSourceInLive,
            )
            return
        }
        SettingsDestination.DOWNLOADS -> {
            DownloadsSettingsScreen(
                onBack = { destination = SettingsDestination.HOME },
            )
            return
        }
        SettingsDestination.HOME -> Unit
    }

    PortraitSettingsMenu(
        summaries = summaries,
        onOpenLiveManagement = { destination = SettingsDestination.LIVE_MANAGEMENT },
        onOpenSources = { destination = SettingsDestination.SOURCES },
        onOpenDownloads = { destination = SettingsDestination.DOWNLOADS },
    )
}
