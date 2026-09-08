package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import app.ownplay.player.OwnPlayAppRuntime

/**
 * Mobile-only presentation entry point.
 *
 * Browsing composition is always portrait. Fullscreen video orientation remains owned by
 * PlaybackWindowController, so sensor fullscreen and PiP are not coupled to browsing layout.
 */
@Composable
internal fun TargetOwnPlayApp(
    runtime: OwnPlayAppRuntime,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
) {
    val currentConfiguration = LocalConfiguration.current
    val portraitConfiguration = Configuration(currentConfiguration).apply {
        orientation = Configuration.ORIENTATION_PORTRAIT
    }
    CompositionLocalProvider(LocalConfiguration provides portraitConfiguration) {
        MobileVNextOwnPlayApp(
            runtime = runtime,
            onPlaybackFullscreenChanged = onPlaybackFullscreenChanged,
            onPlaybackSurfaceActiveChanged = onPlaybackSurfaceActiveChanged,
        )
    }
}
