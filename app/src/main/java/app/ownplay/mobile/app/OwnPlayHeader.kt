package app.ownplay.mobile.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ownplay.mobile.design.OwnPlayColors

@Composable
fun OwnPlayHeader(
    onSearch: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Own",
                    color = OwnPlayColors.OnBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                )
                Text(
                    text = "Play",
                    color = OwnPlayColors.Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                )
            }
            Text(
                text = "Your Channels. Your Way.",
                color = OwnPlayColors.SecondaryText,
                fontSize = 14.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onSearch) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = OwnPlayColors.OnBackground,
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = OwnPlayColors.OnBackground,
            )
        }
    }
}
