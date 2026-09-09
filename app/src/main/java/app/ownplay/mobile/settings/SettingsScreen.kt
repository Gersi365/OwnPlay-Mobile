package app.ownplay.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ownplay.mobile.app.OwnPlayHeader
import app.ownplay.mobile.design.OwnPlayColors

@Composable
fun SettingsScreen() {
    LazyColumn(modifier = Modifier.background(OwnPlayColors.Background)) {
        item { OwnPlayHeader() }
        item {
            Text(
                text = "Settings",
                color = OwnPlayColors.OnBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            Text(
                text = "Personalize your viewing experience",
                color = OwnPlayColors.SecondaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        item { SettingsGroup("Playback", Icons.Filled.PlayArrow, listOf("Picture in Picture", "Resume playback", "Playback quality")) }
        item { SettingsGroup("Live & EPG", Icons.Filled.Podcasts, listOf("Sources", "Provider refresh", "Guide preferences")) }
        item { SettingsGroup("Downloads", Icons.Filled.Download, listOf("Download quality", "Storage", "Manage downloads")) }
        item { SettingsGroup("Appearance", Icons.Filled.Palette, listOf("Theme", "Interface density")) }
        item { SettingsGroup("About", Icons.Filled.Info, listOf("App information", "Help & support", "Privacy")) }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    icon: ImageVector,
    rows: List<String>,
) {
    Surface(
        color = OwnPlayColors.Surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = OwnPlayColors.Accent)
                Text(
                    text = title,
                    color = OwnPlayColors.OnBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        color = OwnPlayColors.Divider,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row,
                        color = OwnPlayColors.OnBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = OwnPlayColors.SecondaryText,
                    )
                }
            }
        }
    }
}
