package app.ownplay.mobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.library.LibraryScreen
import app.ownplay.mobile.live.LiveScreen
import app.ownplay.mobile.settings.SettingsScreen

private enum class PrimaryDestination(
    val label: String,
    val icon: ImageVector,
) {
    LIVE("Live", Icons.Filled.LiveTv),
    LIBRARY("Library", Icons.Filled.VideoLibrary),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun OwnPlayApp() {
    var destination by rememberSaveable { mutableStateOf(PrimaryDestination.LIVE) }

    Scaffold(
        containerColor = OwnPlayColors.Background,
        bottomBar = {
            OwnPlayBottomBar(
                selected = destination,
                onSelect = { destination = it },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (destination) {
                PrimaryDestination.LIVE -> LiveScreen()
                PrimaryDestination.LIBRARY -> LibraryScreen()
                PrimaryDestination.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun OwnPlayBottomBar(
    selected: PrimaryDestination,
    onSelect: (PrimaryDestination) -> Unit,
) {
    Surface(color = OwnPlayColors.Navigation) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            HorizontalDivider(color = OwnPlayColors.Divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp),
            ) {
                PrimaryDestination.entries.forEach { destination ->
                    val active = destination == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable { onSelect(destination) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                    tint = if (active) {
                                        OwnPlayColors.Accent
                                    } else {
                                        OwnPlayColors.SecondaryText
                                    },
                                    modifier = Modifier.size(27.dp),
                                )
                                Text(
                                    text = destination.label,
                                    color = if (active) {
                                        OwnPlayColors.Accent
                                    } else {
                                        OwnPlayColors.SecondaryText
                                    },
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(54.dp)
                                .height(3.dp)
                                .background(
                                    if (active) OwnPlayColors.Accent else OwnPlayColors.Navigation,
                                ),
                        )
                    }
                }
            }
        }
    }
}
