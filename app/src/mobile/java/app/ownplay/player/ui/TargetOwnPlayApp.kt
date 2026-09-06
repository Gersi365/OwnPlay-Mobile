package app.ownplay.player.ui

import androidx.compose.runtime.Composable
import app.ownplay.player.OwnPlayAppRuntime

/** Mobile-only presentation entry point. */
@Composable
internal fun TargetOwnPlayApp(
    runtime: OwnPlayAppRuntime,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
) {
    MobileOwnPlayApp(
        runtime = runtime,
        onPlaybackFullscreenChanged = onPlaybackFullscreenChanged,
        onPlaybackSurfaceActiveChanged = onPlaybackSurfaceActiveChanged,
    )
}
