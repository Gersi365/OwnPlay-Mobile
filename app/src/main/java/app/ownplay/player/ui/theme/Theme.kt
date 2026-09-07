package app.ownplay.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OwnPlayDarkColors = darkColorScheme(
    primary = OwnPlayPalette.Primary,
    onPrimary = OwnPlayPalette.OnPrimary,
    primaryContainer = OwnPlayPalette.PrimaryContainer,
    onPrimaryContainer = OwnPlayPalette.TextPrimary,
    secondary = OwnPlayPalette.PrimarySoft,
    onSecondary = OwnPlayPalette.OnPrimary,
    // Keep navigation selection geometry visually stable: Material3 may draw its indicator,
    // but it blends into the navigation surface while selected state is expressed by tint.
    secondaryContainer = OwnPlayPalette.Surface,
    onSecondaryContainer = OwnPlayPalette.Primary,
    tertiary = OwnPlayPalette.AccentBlue,
    onTertiary = OwnPlayPalette.Background,
    tertiaryContainer = OwnPlayPalette.SurfaceRaised,
    onTertiaryContainer = OwnPlayPalette.TextPrimary,
    background = OwnPlayPalette.Background,
    onBackground = OwnPlayPalette.TextPrimary,
    surface = OwnPlayPalette.Surface,
    onSurface = OwnPlayPalette.TextPrimary,
    surfaceVariant = OwnPlayPalette.SurfaceRaised,
    onSurfaceVariant = OwnPlayPalette.TextSecondary,
    outline = OwnPlayPalette.Outline,
    outlineVariant = OwnPlayPalette.OutlineMuted,
    error = OwnPlayPalette.Error,
    onError = OwnPlayPalette.OnPrimary,
    errorContainer = OwnPlayPalette.SurfaceMuted,
    onErrorContainer = OwnPlayPalette.Error,
)

private val OwnPlayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(OwnPlayMediaLayout.PosterCornerRadius),
    large = RoundedCornerShape(OwnPlayMediaLayout.ContentCornerRadius),
    extraLarge = RoundedCornerShape(OwnPlayMediaLayout.LargeCornerRadius),
)

private val OwnPlayTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.45).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.35.sp,
    ),
)

@Composable
fun OwnPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OwnPlayDarkColors,
        typography = OwnPlayTypography,
        shapes = OwnPlayShapes,
        content = content,
    )
}
