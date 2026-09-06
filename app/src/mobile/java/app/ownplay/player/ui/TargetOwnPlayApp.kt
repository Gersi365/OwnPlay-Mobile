package app.ownplay.player.ui

import androidx.compose.runtime.Composable
import app.ownplay.player.OwnPlayAppRuntime

/** Mobile-only presentation entry point. */
@Composable
internal fun TargetOwnPlayApp(
    runtime: OwnPlayAppRuntime,
    rotationFullscreenEnabled: Boolean,
    onPlaybackFullscreenChanged: (Boolean) -> Unit,
    onPlaybackSurfaceActiveChanged: (Boolean) -> Unit,
    onLivePreviewActiveChanged: (Boolean) -> Unit,
) {
    MobileOwnPlayApp(
        runtime = runtime,
        rotationFullscreenEnabled = rotationFullscreenEnabled,
        onPlaybackFullscreenChanged = onPlaybackFullscreenChanged,
        onPlaybackSurfaceActiveChanged = onPlaybackSurfaceActiveChanged,
        onLivePreviewActiveChanged = onLivePreviewActiveChanged,
    )
}
