package app.ownplay.player.ui

import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.playback.PlaybackPresentationPolicy
import app.ownplay.player.playback.PlaybackState
import kotlinx.coroutines.delay
import kotlin.math.max

private const val ON_DEMAND_CONTROLS_AUTO_HIDE_MILLIS = 3_000L

@OptIn(UnstableApi::class)
@Composable
internal fun OnDemandPlaybackSurface(
    runtime: OwnPlayAppRuntime,
    contentKey: String,
    title: String,
    playbackState: PlaybackState,
    currentPositionMs: Long,
    durationMs: Long,
    exitRequested: Boolean,
    onExit: () -> Unit,
    onPlayerViewAvailable: (PlayerView) -> Unit,
    onPlayerViewReleased: (PlayerView) -> Unit,
    onSeekPositionChanged: (Long) -> Unit,
) {
    val playbackControls = PlaybackPresentationPolicy.controlsFor(playbackState)
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val backFocusRequester = remember(contentKey) { FocusRequester() }
    val controlsFocusRequester = remember(contentKey) { FocusRequester() }
    val wakeFocusRequester = remember(contentKey) { FocusRequester() }
    var playerView by remember(contentKey) { mutableStateOf<PlayerView?>(null) }
    var controlsVisible by remember(contentKey) { mutableStateOf(true) }
    var controlsInteractionToken by remember(contentKey) { mutableStateOf(0) }
    var scrubPositionMs by remember(contentKey) { mutableStateOf(currentPositionMs.coerceAtLeast(0L)) }
    var scrubbing by remember(contentKey) { mutableStateOf(false) }

    fun revealControls() {
        controlsVisible = true
        controlsInteractionToken += 1
    }

    LaunchedEffect(currentPositionMs, scrubbing, contentKey) {
        if (!scrubbing) {
            scrubPositionMs = currentPositionMs.coerceAtLeast(0L)
        }
    }

    LaunchedEffect(playbackState, controlsVisible, controlsInteractionToken, contentKey) {
        when (playbackState) {
            is PlaybackState.Playing -> {
                if (controlsVisible) {
                    delay(ON_DEMAND_CONTROLS_AUTO_HIDE_MILLIS)
                    controlsVisible = false
                }
            }
            is PlaybackState.Loading,
            is PlaybackState.Paused,
            is PlaybackState.Failed,
            -> controlsVisible = true
            PlaybackState.Idle -> Unit
        }
    }

    LaunchedEffect(isTelevision, controlsVisible, playbackState, contentKey) {
        if (!isTelevision) return@LaunchedEffect
        when {
            playbackState is PlaybackState.Failed -> backFocusRequester.requestFocus()
            controlsVisible -> controlsFocusRequester.requestFocus()
            else -> wakeFocusRequester.requestFocus()
        }
    }

    val remoteWakeModifier = if (isTelevision && !controlsVisible) {
        Modifier
            .focusRequester(wakeFocusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.isOnDemandRemoteNavigationKeyDown()) {
                    revealControls()
                    true
                } else {
                    false
                }
            }
            .focusable()
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (
                        isTelevision &&
                        controlsVisible &&
                        event.nativeKeyEvent.isOnDemandRemoteNavigationKeyDown()
                    ) {
                        controlsInteractionToken += 1
                    }
                    false
                },
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).also { view ->
                        view.useController = false
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        view.setShutterBackgroundColor(AndroidColor.BLACK)
                        PlaybackInteractionBridge.bind(
                            output = runtime.playbackVideoOutput,
                            view = view,
                            showNativeController = false,
                        )
                        playerView = view
                        onPlayerViewAvailable(view)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.useController = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlaybackInteractionBridge.bind(
                        output = runtime.playbackVideoOutput,
                        view = view,
                        showNativeController = false,
                    )
                    playerView = view
                    onPlayerViewAvailable(view)
                },
                onRelease = { view ->
                    PlaybackInteractionBridge.unbind(runtime.playbackVideoOutput, view)
                    if (playerView === view) playerView = null
                    onPlayerViewReleased(view)
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(contentKey, controlsVisible) {
                        detectTapGestures {
                            if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                revealControls()
                            }
                        }
                    }
                    .then(remoteWakeModifier),
            )

            if (controlsVisible) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        modifier = Modifier.focusRequester(backFocusRequester),
                        enabled = !exitRequested,
                        onClick = onExit,
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.70f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val maxDuration = max(durationMs, 1L)
                    Slider(
                        value = scrubPositionMs.coerceIn(0L, maxDuration).toFloat(),
                        onValueChange = { value ->
                            scrubbing = true
                            scrubPositionMs = value.toLong()
                            revealControls()
                        },
                        onValueChangeFinished = {
                            val target = scrubPositionMs.coerceIn(0L, maxDuration)
                            playerView?.player?.seekTo(target)
                            onSeekPositionChanged(target)
                            scrubbing = false
                            revealControls()
                        },
                        valueRange = 0f..maxDuration.toFloat(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val playbackActionEnabled =
                            playbackState is PlaybackState.Playing ||
                                playbackState is PlaybackState.Paused ||
                                (playbackState is PlaybackState.Failed && playbackControls.canRetry)
                        IconButton(
                            modifier = Modifier.focusRequester(controlsFocusRequester),
                            enabled = playbackActionEnabled,
                            onClick = {
                                when (playbackState) {
                                    is PlaybackState.Playing -> runtime.playbackController.pause()
                                    is PlaybackState.Paused -> runtime.playbackController.play()
                                    is PlaybackState.Failed -> if (playbackControls.canRetry) {
                                        runtime.playbackController.retry()
                                    }
                                    else -> Unit
                                }
                                revealControls()
                            },
                        ) {
                            val playing = playbackState is PlaybackState.Playing
                            val failed = playbackState is PlaybackState.Failed
                            Icon(
                                imageVector = when {
                                    failed -> Icons.Filled.Refresh
                                    playing -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = when {
                                    failed -> "Retry"
                                    playing -> "Pause"
                                    else -> "Play"
                                },
                                tint = if (playbackActionEnabled) {
                                    Color.White
                                } else {
                                    Color.White.copy(alpha = 0.38f)
                                },
                            )
                        }
                        Text(
                            text = "${formatOnDemandDuration(scrubPositionMs)} / ${formatOnDemandDuration(durationMs)}",
                            color = Color.White.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        when (playbackState) {
                            is PlaybackState.Loading -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            is PlaybackState.Failed -> Text(
                                text = "Playback failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

private fun KeyEvent.isOnDemandRemoteNavigationKeyDown(): Boolean =
    action == KeyEvent.ACTION_DOWN &&
        keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )

private fun formatOnDemandDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "00:00"
    val totalSeconds = milliseconds / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
