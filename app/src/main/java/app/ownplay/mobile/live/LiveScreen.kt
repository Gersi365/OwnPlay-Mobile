package app.ownplay.mobile.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ownplay.mobile.app.OwnPlayHeader
import app.ownplay.mobile.design.OwnPlayColors

@Composable
fun LiveScreen() {
    LazyColumn(
        modifier = Modifier.background(OwnPlayColors.Background),
    ) {
        item { OwnPlayHeader() }
        item { PreviewEmptyState() }
        item {
            Text(
                text = "All Channels",
                color = OwnPlayColors.OnBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }
        item { ChannelEmptyState() }
    }
}

@Composable
private fun PreviewEmptyState() {
    Surface(
        color = OwnPlayColors.Surface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(OwnPlayColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LiveTv,
                    contentDescription = null,
                    tint = OwnPlayColors.MutedText,
                    modifier = Modifier.size(54.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Preview", color = OwnPlayColors.SecondaryText)
                    Text(
                        text = "Choose a channel",
                        color = OwnPlayColors.OnBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Next", color = OwnPlayColors.SecondaryText)
                    Text("—", color = OwnPlayColors.OnBackground)
                }
            }
        }
    }
}

@Composable
private fun ChannelEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        HorizontalDivider(color = OwnPlayColors.Divider)
        Text(
            text = "No source configured yet.",
            color = OwnPlayColors.OnBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            text = "Add an Xtream or M3U source from Settings.",
            color = OwnPlayColors.SecondaryText,
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
        )
    }
}
