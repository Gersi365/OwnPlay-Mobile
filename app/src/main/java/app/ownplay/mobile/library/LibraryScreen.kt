package app.ownplay.mobile.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ownplay.mobile.app.OwnPlayHeader
import app.ownplay.mobile.design.OwnPlayColors

@Composable
fun LibraryScreen() {
    LazyColumn {
        item { OwnPlayHeader() }
        item { ContinueWatchingEmpty() }
        item { MediaSection(title = "Movies") }
        item { MediaSection(title = "Series") }
        item { MediaSection(title = "Downloaded Media") }
    }
}

@Composable
private fun ContinueWatchingEmpty() {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        SectionHeading("Continue Watching")
        Surface(
            color = OwnPlayColors.Surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Nothing to resume yet",
                    color = OwnPlayColors.OnBackground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Your in-progress movies and episodes will appear here.",
                    color = OwnPlayColors.SecondaryText,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaSection(title: String) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        SectionHeading(title)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Surface(
                    color = OwnPlayColors.SurfaceElevated,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(150.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        color = OwnPlayColors.OnBackground,
        fontSize = 23.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
