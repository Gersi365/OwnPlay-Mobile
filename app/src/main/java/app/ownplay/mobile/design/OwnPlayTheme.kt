package app.ownplay.mobile.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OwnPlayDarkColors = darkColorScheme(
    primary = OwnPlayColors.Accent,
    background = OwnPlayColors.Background,
    onBackground = OwnPlayColors.TextPrimary,
    surface = OwnPlayColors.Surface,
    onSurface = OwnPlayColors.TextPrimary,
    surfaceVariant = OwnPlayColors.SurfaceRaised,
    onSurfaceVariant = OwnPlayColors.TextSecondary,
    error = OwnPlayColors.Error,
)

@Composable
fun OwnPlayTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OwnPlayDarkColors,
        content = content,
    )
}
