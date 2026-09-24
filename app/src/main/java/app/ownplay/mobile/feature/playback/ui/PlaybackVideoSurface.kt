package app.ownplay.mobile.feature.playback.ui

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackPresentation
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import kotlin.math.max

internal enum class PlaybackVideoContentMode {
    FIT,
    FILL,
}

@Composable
internal fun PlaybackVideoSurface(
    playbackEngine: Media3PlaybackEngine,
    modifier: Modifier = Modifier,
    contentMode: PlaybackVideoContentMode = PlaybackVideoContentMode.FIT,
    showFullscreenControls: Boolean = true,
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val playbackSessionController = remember(application) {
        application.services.playbackSessionController
    }
    val playbackState by playbackSessionController.state.collectAsState()
    val videoAspectRatio by playbackEngine.videoAspectRatio.collectAsState()
    val surfaceView = remember(playbackEngine, context) { SurfaceView(context) }

    DisposableEffect(playbackEngine, surfaceView) {
        playbackEngine.attachVideoSurface(surfaceView)
        onDispose {
            playbackEngine.detachVideoSurface(surfaceView)
        }
    }

    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val videoAspect = videoAspectRatio?.takeIf { it.isFinite() && it > 0f }
        val containerAspect = if (maxHeight.value > 0f) {
            maxWidth.value / maxHeight.value
        } else {
            1f
        }
        val fitModifier = when {
            videoAspect == null -> Modifier.fillMaxSize()
            videoAspect >= containerAspect ->
                Modifier.fillMaxWidth().aspectRatio(videoAspect)
            else ->
                Modifier.fillMaxHeight().aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
        }
        val fillScale = if (contentMode == PlaybackVideoContentMode.FILL && videoAspect != null) {
            if (videoAspect >= containerAspect) {
                max(1f, videoAspect / containerAspect)
            } else {
                max(1f, containerAspect / videoAspect)
            }
        } else {
            1f
        }

        AndroidView(
            factory = { surfaceView },
            modifier = fitModifier.graphicsLayer {
                scaleX = fillScale
                scaleY = fillScale
            },
        )

        if (
            showFullscreenControls &&
            playbackState.presentation == PlaybackPresentation.FULLSCREEN
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlaybackTrackControlsOverlay(
                    state = playbackState,
                    controller = playbackSessionController,
                    showTransportControls = playbackState.target is PlaybackTarget.Library,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

