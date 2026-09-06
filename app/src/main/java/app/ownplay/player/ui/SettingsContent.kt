package app.ownplay.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.BuildConfig
import app.ownplay.player.persistence.PlaylistSourceSummary

@Composable
internal fun ContentSettingsContent(
    summaries: List<PlaylistSourceSummary>,
    onOpenLiveManagement: () -> Unit,
    onOpenPlaylists: () -> Unit,
) {
    SettingsActionRow(
        title = "Live management",
        detail = "Categories, channels and custom groups",
        actionLabel = "Manage",
        onClick = onOpenLiveManagement,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SettingsActionRow(
        title = "Playlists",
        detail = "${summaries.size} configured · add, edit and refresh sources",
        actionLabel = "Manage",
        onClick = onOpenPlaylists,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    BackupRestoreSettingsContent()
}

@Composable
internal fun AboutSettingsContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "OwnPlay plays and organizes media sources you add.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "It does not provide channels, subscriptions or IPTV services.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingValueRow(
            label = "Version",
            value = BuildConfig.VERSION_NAME,
        )
    }
}
