package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.personalization.AppOrientationMode
import app.ownplay.player.personalization.AppOrientationStore
import app.ownplay.player.source.SourceSyncState
import kotlinx.coroutines.launch

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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val readySummaries = summaries.filter { summary -> summary.enabled }

    val context = LocalContext.current
    val orientationStore = remember(context) {
        AppOrientationStore(context.applicationContext)
    }
    val orientationMode by orientationStore.observe().collectAsState(
        initial = AppOrientationMode.PORTRAIT,
    )
    val scope = rememberCoroutineScope()

    val nestedDestinationBackEnabled =
        destination == SettingsDestination.LIVE_MANAGEMENT ||
            destination == SettingsDestination.PLAYLISTS ||
            (!isLandscape && destination == SettingsDestination.DOWNLOADS)
    BackHandler(enabled = nestedDestinationBackEnabled) {
        destination = SettingsDestination.CONTENT
    }

    if (isLandscape) {
        LandscapeSettingsShell(
            destination = destination,
            onDestinationChange = { destination = it },
            runtime = runtime,
            summaries = summaries,
            syncState = syncState,
            orientationMode = orientationMode,
            onSetOrientation = { mode ->
                scope.launch { orientationStore.set(mode) }
            },
            onOpenSourceInLive = onOpenSourceInLive,
        )
        return
    }

    when (destination) {
        SettingsDestination.LIVE_MANAGEMENT -> {
            LiveManagementScreen(
                runtime = runtime,
                summaries = readySummaries,
                onBack = { destination = SettingsDestination.CONTENT },
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
            )
            return
        }
        SettingsDestination.DOWNLOADS -> {
            DownloadsSettingsScreen(
                onBack = { destination = SettingsDestination.CONTENT },
            )
            return
        }
        else -> Unit
    }

    PortraitSettingsMenu(
        orientationMode = orientationMode,
        onSetOrientation = { mode ->
            scope.launch { orientationStore.set(mode) }
        },
        summaries = summaries,
        onOpenLiveManagement = { destination = SettingsDestination.LIVE_MANAGEMENT },
        onOpenPlaylists = { destination = SettingsDestination.PLAYLISTS },
        onOpenDownloads = { destination = SettingsDestination.DOWNLOADS },
    )
}
