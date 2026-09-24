package app.ownplay.mobile.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors

@Composable
internal fun LibraryCastSection(
    cast: List<String>,
) {
    val names = cast.asSequence()
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (names.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Cast",
            color = OwnPlayColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = names.joinToString(" • "),
            color = OwnPlayColors.TextSecondary,
        )
    }
}
