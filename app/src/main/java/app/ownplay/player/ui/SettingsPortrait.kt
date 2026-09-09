package app.ownplay.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.persistence.PlaylistSourceSummary

@Composable
internal fun PortraitSettingsMenu(
    summaries: List<PlaylistSourceSummary>,
    onOpenLiveManagement: () -> Unit,
    onOpenSources: () -> Unit,
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
                .widthIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Sources, offline media and app data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            VNextSettingsSection(
                title = "Sources",
                subtitle = "Providers and Live organization",
            ) {
                SourcesSettingsContent(
                    summaries = summaries,
                    onOpenLiveManagement = onOpenLiveManagement,
                    onOpenSources = onOpenSources,
                )
            }

            VNextSettingsSection(
                title = "Downloads",
                subtitle = "Offline media and transfer state",
            ) {
                SettingsActionRow(
                    title = "Downloads",
                    detail = "Manage active and completed offline media",
                    actionLabel = "Open downloads",
                    onClick = onOpenDownloads,
                )
            }

            VNextSettingsSection(
                title = "Backup & Restore",
                subtitle = "Personalization backup only",
            ) {
                BackupRestoreSettingsContent()
            }

            VNextSettingsSection(
                title = "About",
                subtitle = "App identity and version",
            ) {
                AboutSettingsContent()
            }
        }
    }
}
