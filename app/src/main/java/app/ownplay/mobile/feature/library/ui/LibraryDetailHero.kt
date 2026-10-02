package app.ownplay.mobile.feature.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader

@Composable
internal fun LibraryDetailHero(
    title: String,
    artworkUrl: String?,
    artworkLoader: LibraryArtworkLoader,
    metadataLine: String?,
    description: String?,
    supportingLine: String? = null,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            color = OwnPlayColors.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stackContent = shouldStackLibraryDetailHero(
                availableWidth = maxWidth,
                fontScale = LocalDensity.current.fontScale,
            )
            if (stackContent) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LibraryArtwork(
                        url = artworkUrl,
                        loader = artworkLoader,
                        largePoster = true,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    LibraryDetailHeroMetadata(
                        metadataLine = metadataLine,
                        supportingLine = supportingLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LibraryArtwork(
                        url = artworkUrl,
                        loader = artworkLoader,
                        largePoster = true,
                    )
                    LibraryDetailHeroMetadata(
                        metadataLine = metadataLine,
                        supportingLine = supportingLine,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        actions()

        description
            ?.takeIf(String::isNotBlank)
            ?.let { value ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Overview",
                        color = OwnPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = value,
                        color = OwnPlayColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
    }
}

@Composable
private fun LibraryDetailHeroMetadata(
    metadataLine: String?,
    supportingLine: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        metadataLine
            ?.takeIf(String::isNotBlank)
            ?.let { line ->
                Text(
                    text = line,
                    color = OwnPlayColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        supportingLine
            ?.takeIf(String::isNotBlank)
            ?.let { line ->
                Text(
                    text = line,
                    color = OwnPlayColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}

internal fun shouldStackLibraryDetailHero(
    availableWidth: Dp,
    fontScale: Float,
): Boolean = availableWidth < 336.dp || fontScale >= 1.2f
