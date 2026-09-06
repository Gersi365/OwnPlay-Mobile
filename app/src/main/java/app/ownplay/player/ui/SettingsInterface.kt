package app.ownplay.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.personalization.AppDeviceProfile
import app.ownplay.player.personalization.AppOrientationMode

@Composable
internal fun PortraitSettingsMenu(
    deviceProfile: AppDeviceProfile?,
    orientationMode: AppOrientationMode,
    onSetOrientation: (AppOrientationMode) -> Unit,
    summaries: List<PlaylistSourceSummary>,
    onOpenLiveManagement: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = "OWNPLAY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Personalize the player, organize your sources and manage offline media.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CompactSettingsSection(
                icon = Icons.Filled.Tune,
                title = "Interface",
                subtitle = "Device behavior and orientation",
            ) {
                InterfaceSettingsContent(
                    deviceProfile = deviceProfile,
                    orientationMode = orientationMode,
                    onSetOrientation = onSetOrientation,
                )
            }

            CompactSettingsSection(
                icon = Icons.Filled.Folder,
                title = "Content",
                subtitle = "Live organization, playlists and personalization",
            ) {
                ContentSettingsContent(
                    summaries = summaries,
                    onOpenLiveManagement = onOpenLiveManagement,
                    onOpenPlaylists = onOpenPlaylists,
                )
            }

            CompactSettingsSection(
                icon = Icons.Filled.Download,
                title = "Downloads",
                subtitle = "Movies and episodes available offline",
            ) {
                SettingsActionRow(
                    title = "Downloaded media",
                    detail = "View progress, retry downloads or remove saved media",
                    actionLabel = "Open downloads",
                    onClick = onOpenDownloads,
                )
            }

            CompactSettingsSection(
                icon = Icons.Filled.Info,
                title = "About",
                subtitle = "OwnPlay and build information",
            ) {
                AboutSettingsContent()
            }
        }
    }
}

@Composable
internal fun InterfaceSettingsContent(
    deviceProfile: AppDeviceProfile?,
    orientationMode: AppOrientationMode,
    onSetOrientation: (AppOrientationMode) -> Unit,
) {
    SettingValueRow(
        label = "Device target",
        value = when (deviceProfile) {
            AppDeviceProfile.SMARTPHONE -> "Mobile"
            AppDeviceProfile.ANDROID_TV -> "TV"
            null -> "Loading…"
        },
    )
    Text(
        text = when (deviceProfile) {
            AppDeviceProfile.SMARTPHONE ->
                "This build uses touch-first Mobile controls."
            AppDeviceProfile.ANDROID_TV ->
                "This build uses remote-first TV controls."
            null -> "Loading device target…"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SettingValueRow(
        label = "Orientation",
        value = when (deviceProfile) {
            AppDeviceProfile.SMARTPHONE -> if (orientationMode == AppOrientationMode.LANDSCAPE) {
                "Landscape"
            } else {
                "Portrait"
            }
            AppDeviceProfile.ANDROID_TV -> "Landscape · fixed"
            null -> "Loading…"
        },
    )
    if (deviceProfile == AppDeviceProfile.SMARTPHONE) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OrientationButton(
                label = "Portrait",
                selected = orientationMode == AppOrientationMode.PORTRAIT,
                onClick = { onSetOrientation(AppOrientationMode.PORTRAIT) },
                modifier = Modifier.weight(1f),
            )
            OrientationButton(
                label = "Landscape",
                selected = orientationMode == AppOrientationMode.LANDSCAPE,
                onClick = { onSetOrientation(AppOrientationMode.LANDSCAPE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text(
        text = when (deviceProfile) {
            AppDeviceProfile.SMARTPHONE ->
                "With an active Live preview, rotating to landscape can open the full player."
            AppDeviceProfile.ANDROID_TV ->
                "TV stays landscape and uses the D-pad/remote layout."
            null -> "Loading orientation…"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun deviceProfileLabel(profile: AppDeviceProfile?): String = when (profile) {
    AppDeviceProfile.SMARTPHONE -> "Smartphone"
    AppDeviceProfile.ANDROID_TV -> "Android TV"
    null -> "Loading…"
}
