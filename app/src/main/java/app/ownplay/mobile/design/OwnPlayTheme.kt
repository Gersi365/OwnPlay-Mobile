package app.ownplay.mobile.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OwnPlayColors {
    val Background = Color(0xFF070B12)
    val Surface = Color(0xFF0D141E)
    val SurfaceElevated = Color(0xFF111C29)
    val Navigation = Color(0xFF090F17)
    val Divider = Color(0xFF223040)
    val Accent = Color(0xFF1495FF)
    val AccentStrong = Color(0xFF0879F9)
    val OnBackground = Color(0xFFF6F8FC)
    val SecondaryText = Color(0xFFA7B3C5)
    val MutedText = Color(0xFF738196)
    val Error = Color(0xFFFF5A6F)
}

private val OwnPlayDarkScheme = darkColorScheme(
    primary = OwnPlayColors.Accent,
    onPrimary = Color.White,
    secondary = OwnPlayColors.AccentStrong,
    background = OwnPlayColors.Background,
    onBackground = OwnPlayColors.OnBackground,
    surface = OwnPlayColors.Surface,
    onSurface = OwnPlayColors.OnBackground,
    surfaceVariant = OwnPlayColors.SurfaceElevated,
    onSurfaceVariant = OwnPlayColors.SecondaryText,
    error = OwnPlayColors.Error,
)

@Composable
fun OwnPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OwnPlayDarkScheme,
        content = content,
    )
}
