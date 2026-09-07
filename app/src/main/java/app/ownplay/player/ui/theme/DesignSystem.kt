package app.ownplay.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object OwnPlayPalette {
    val Background = Color(0xFF07070D)
    val Surface = Color(0xFF0D0C16)
    val SurfaceRaised = Color(0xFF151326)
    val SurfaceMuted = Color(0xFF1B1930)
    val Primary = Color(0xFF8B5CF6)
    val PrimarySoft = Color(0xFFB9A3FF)
    val PrimaryContainer = Color(0xFF2B1D4A)
    val OnPrimary = Color(0xFF150B28)
    val TextPrimary = Color(0xFFF7F5FC)
    val TextSecondary = Color(0xFFBCB7C9)
    val Outline = Color(0xFF39354A)
    val OutlineMuted = Color(0xFF272333)
    val AccentBlue = Color(0xFF76C7FF)
    val Error = Color(0xFFFFB4AB)
}

internal object OwnPlaySpacing {
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp
}

internal object OwnPlayMotion {
    const val PressMillis = 120
    const val PosterCrossfadeMillis = 160
    const val ContentTransitionMillis = 200
    const val SheetTransitionMillis = 240
}

internal object OwnPlayMediaLayout {
    const val PosterAspectRatio = 2f / 3f
    const val MinimumPosterWidthDp = 92
    const val GridGapDp = 8
    const val HorizontalPaddingDp = 12
    const val MaximumGridColumns = 8

    val PosterCornerRadius = 10.dp
    val ContentCornerRadius = 14.dp
    val LargeCornerRadius = 20.dp
}

internal fun posterColumnCountForWidthDp(availableWidthDp: Int): Int {
    if (availableWidthDp <= 0) return 1
    val contentWidth = (availableWidthDp - OwnPlayMediaLayout.HorizontalPaddingDp * 2)
        .coerceAtLeast(1)
    val slotWidth = OwnPlayMediaLayout.MinimumPosterWidthDp + OwnPlayMediaLayout.GridGapDp
    val calculated = (contentWidth + OwnPlayMediaLayout.GridGapDp) / slotWidth
    return calculated
        .coerceAtLeast(if (availableWidthDp >= 320) 3 else 1)
        .coerceAtMost(OwnPlayMediaLayout.MaximumGridColumns)
}
