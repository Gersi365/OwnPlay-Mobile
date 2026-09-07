package app.ownplay.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shared immersive fullscreen behavior for non-Live playback surfaces.
 *
 * Fullscreen video hides both system bars while keeping Android's transient swipe reveal behavior.
 * Leaving the surface restores the bars so ordinary portrait browsing owns normal system chrome.
 */
@Composable
internal fun PlayerFullscreenSystemBarsEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val activity = context.findPlaybackActivity()
        val controller = activity?.let { host ->
            WindowCompat.getInsetsController(host.window, host.window.decorView)
        }

        if (enabled) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            if (enabled) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findPlaybackActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPlaybackActivity()
    else -> null
}
