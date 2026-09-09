package app.ownplay.player.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    var playerView by remember(contentKey) { mutableStateOf<PlayerView?>(null) }
    var controlsVisible by remember(contentKey) { mutableStateOf(true) }
    var controlsInteractionToken by remember(contentKey) { mutableStateOf(0) }
    var scrubPositionMs by remember(contentKey) { mutableStateOf(currentPositionMs.coerceAtLeast(0L)) }
    var scrubbing by remember(contentKey) { mutableStateOf(false) }

    PlayerFullscreenSystemBarsEffect(enabled = true)

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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
                    },
            )

            if (playbackControls.showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (playbackState is PlaybackState.Failed) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = "Playback failed",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopStart),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VNextMediaIconAction(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            enabled = !exitRequested,
                            onClick = onExit,
                        )
                        Text(
                            text = title,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.82f),
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                            ),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val playbackActionEnabled =
                                playbackState is PlaybackState.Playing ||
                                    playbackState is PlaybackState.Paused ||
                                    (playbackState is PlaybackState.Failed && playbackControls.canRetry)
                            val playing = playbackState is PlaybackState.Playing
                            val failed = playbackState is PlaybackState.Failed
                            VNextMediaIconAction(
                                icon = when {
                                    failed -> Icons.Filled.Refresh
                                    playing -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = when {
                                    failed -> "Retry"
                                    playing -> "Pause"
                                    else -> "Play"
                                },
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
                            )
                            Text(
                                text = "${formatOnDemandDuration(scrubPositionMs)} / ${formatOnDemandDuration(durationMs)}",
                                color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

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
