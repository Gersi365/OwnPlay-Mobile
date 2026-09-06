package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.personalization.AppDeviceProfile
import app.ownplay.player.personalization.AppOrientationMode
import app.ownplay.player.source.SourceSyncState

@Composable
internal fun LandscapeSettingsShell(
    destination: SettingsDestination,
    onDestinationChange: (SettingsDestination) -> Unit,
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    syncState: SourceSyncState,
    deviceProfile: AppDeviceProfile?,
    orientationMode: AppOrientationMode,
    onSetOrientation: (AppOrientationMode) -> Unit,
    onOpenSourceInLive: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val resolvedDestination = if (isTelevision && destination == SettingsDestination.DOWNLOADS) {
        SettingsDestination.CONTENT
    } else {
        destination
    }
    val selectedRailFocusRequester = remember { FocusRequester() }
    val selectedRailDestination = when (resolvedDestination) {
        SettingsDestination.LIVE_MANAGEMENT,
        SettingsDestination.PLAYLISTS,
        -> SettingsDestination.CONTENT
        else -> resolvedDestination
    }
    val readySummaries = summaries.filter { summary -> summary.enabled }

    LaunchedEffect(isTelevision, resolvedDestination) {
        if (isTelevision) {
            selectedRailFocusRequester.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LandscapeSettingsRail(
            selectedRailDestination = selectedRailDestination,
            selectedRailFocusRequester = selectedRailFocusRequester,
            onDestinationChange = onDestinationChange,
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f),
            tonalElevation = 0.dp,
        ) {
            when (resolvedDestination) {
                SettingsDestination.INTERFACE -> LandscapeSectionPage(
                    title = "Interface",
                    subtitle = "Device behavior and orientation",
                ) {
                    InterfaceSettingsContent(
                        deviceProfile = deviceProfile,
                        orientationMode = orientationMode,
                        onSetOrientation = onSetOrientation,
                    )
                }

                SettingsDestination.CONTENT -> LandscapeSectionPage(
                    title = "Content",
                    subtitle = "Live organization, playlists and backup",
                ) {
                    ContentSettingsContent(
                        summaries = summaries,
                        onOpenLiveManagement = {
                            onDestinationChange(SettingsDestination.LIVE_MANAGEMENT)
                        },
                        onOpenPlaylists = {
                            onDestinationChange(SettingsDestination.PLAYLISTS)
                        },
                    )
                }

                SettingsDestination.DOWNLOADS -> DownloadsSettingsScreen()

                SettingsDestination.ABOUT -> LandscapeSectionPage(
                    title = "About",
                    subtitle = "OwnPlay and build information",
                ) {
                    AboutSettingsContent()
                }

                SettingsDestination.LIVE_MANAGEMENT -> LiveManagementScreen(
                    runtime = runtime,
                    summaries = readySummaries,
                    onBack = { onDestinationChange(SettingsDestination.CONTENT) },
                )

                SettingsDestination.PLAYLISTS -> PlaylistManagementSubscreen(
                    runtime = runtime,
                    summaries = summaries,
                    syncState = syncState,
                    onBack = { onDestinationChange(SettingsDestination.CONTENT) },
                    onOpenInLive = onOpenSourceInLive,
                )
            }
        }
    }
}

@Composable
internal fun LandscapeSectionPage(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "OWNPLAY",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
