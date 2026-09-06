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
import app.ownplay.player.personalization.AppOrientationMode

@Composable
internal fun PortraitSettingsMenu(
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
                subtitle = "Orientation",
            ) {
                InterfaceSettingsContent(
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
    orientationMode: AppOrientationMode,
    onSetOrientation: (AppOrientationMode) -> Unit,
) {
    SettingValueRow(
        label = "Orientation",
        value = if (orientationMode == AppOrientationMode.LANDSCAPE) {
            "Landscape"
        } else {
            "Portrait"
        },
    )
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = "With an active Live preview, rotating to landscape can open the full player.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
