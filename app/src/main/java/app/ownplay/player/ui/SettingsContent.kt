package app.ownplay.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.BuildConfig
import app.ownplay.player.persistence.PlaylistSourceSummary

@Composable
internal fun SourcesSettingsContent(
    summaries: List<PlaylistSourceSummary>,
    onOpenLiveManagement: () -> Unit,
    onOpenSources: () -> Unit,
) {
    SettingsActionRow(
        title = "Sources",
        detail = "${summaries.size} configured · add, edit and refresh",
        actionLabel = "Open sources",
        onClick = onOpenSources,
    )
    SettingsActionRow(
        title = "Live organization",
        detail = "Categories, channels and custom groups",
        actionLabel = "Open Live organization",
        onClick = onOpenLiveManagement,
    )
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
        SettingValueRow(
            label = "Version",
            value = BuildConfig.VERSION_NAME,
        )
    }
}
