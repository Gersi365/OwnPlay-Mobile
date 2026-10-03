package app.ownplay.mobile.feature.live.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.feature.playback.ui.FullscreenPlaybackWindow
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.feature.live.domain.LiveGuidePolicy
import app.ownplay.mobile.feature.live.domain.LiveNowNext
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionState
import app.ownplay.mobile.feature.playback.ui.PlaybackTrackControlsOverlay
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoContentMode
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoSurface
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveFullscreenPresentation(
    channelName: String,
    guide: LiveNowNext,
    nowEpochSeconds: Long,
    playbackState: PlaybackSessionState,
    playbackSessionController: PlaybackSessionController,
    playbackEngine: Media3PlaybackEngine,
    orderedChannelIds: List<String>,
    currentChannelId: String,
    lastKnownIndex: Int?,
    channelNameForId: (String) -> String,
    playerVolume: Float,
    onPlayerVolumeCommitted: (Float) -> Unit,
    onSwitchChannel: (String) -> Unit,
    onRetry: () -> Unit,
    catchUpEnabled: Boolean,
    onCatchUp: () -> Unit,
    onRecordNow: () -> Unit,
    onScheduleNext: () -> Unit,
    onPictureInPicture: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val switchThresholdPx = with(density) { 64.dp.toPx() }
    val directionThresholdPx = with(density) { 28.dp.toPx() }

    var contentMode by remember { mutableStateOf(PlaybackVideoContentMode.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var dragStartedOnLeft by remember { mutableStateOf(true) }
    var dragStartBrightness by remember { mutableFloatStateOf(0.5f) }
    var dragStartVolume by remember { mutableFloatStateOf(playerVolume) }
    var localVolume by remember { mutableFloatStateOf(playerVolume) }
    var candidateChannelId by remember { mutableStateOf<String?>(null) }
    var switchedChannelName by remember { mutableStateOf<String?>(null) }
    var channelOverlayForward by remember { mutableStateOf(true) }
    var lastChannelSwitchAtMs by remember { mutableLongStateOf(0L) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    var showPreparingIndicator by remember { mutableStateOf(false) }

    fun registerInteraction() {
        controlsVisible = true
        interactionRevision += 1
    }

    LaunchedEffect(playerVolume) {
        localVolume = playerVolume.coerceIn(0f, 1f)
    }
    LaunchedEffect(playbackState.readiness) {
        showPreparingIndicator = false
        if (playbackState.readiness == PlaybackReadiness.PREPARING) {
            delay(280L)
            showPreparingIndicator = true
        }
    }
    LaunchedEffect(switchedChannelName) {
        if (switchedChannelName != null) {
            delay(900L)
            switchedChannelName = null
        }
    }
    LaunchedEffect(controlsVisible, panelOpen, interactionRevision) {
        if (controlsVisible && !panelOpen) {
            delay(3_000L)
            controlsVisible = false
        }
    }
    LaunchedEffect(gestureFeedback) {
        if (gestureFeedback != null && candidateChannelId == null) {
            delay(900L)
            gestureFeedback = null
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.let(::restoreSystemBrightness)
        }
    }

    FullscreenPlaybackWindow(onDismissRequest = onDismiss) {
        Surface(
            color = OwnPlayColors.Background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = ::registerInteraction,
                        onDoubleClick = {
                            contentMode = if (contentMode == PlaybackVideoContentMode.FIT) {
                                PlaybackVideoContentMode.FILL
                            } else {
                                PlaybackVideoContentMode.FIT
                            }
                            registerInteraction()
                        },
                    )
                    .pointerInput(
                        orderedChannelIds,
                        currentChannelId,
                        lastKnownIndex,
                        localVolume,
                    ) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragDx = 0f
                                dragDy = 0f
                                dragStartedOnLeft = offset.x < size.width / 2f
                                dragStartBrightness = currentWindowBrightness(context)
                                dragStartVolume = localVolume
                                candidateChannelId = null
                                gestureFeedback = null
                            },
                            onDragCancel = {
                                if (!dragStartedOnLeft) {
                                    onPlayerVolumeCommitted(localVolume)
                                }
                                candidateChannelId = null
                                registerInteraction()
                            },
                            onDragEnd = {
                                val axis = LiveFullscreenInteractionPolicy.classifyDrag(
                                    totalDx = dragDx,
                                    totalDy = dragDy,
                                    minimumDistancePx = directionThresholdPx,
                                )
                                if (
                                    axis == LiveFullscreenGestureAxis.HORIZONTAL &&
                                    abs(dragDx) >= switchThresholdPx
                                ) {
                                    val step = LiveFullscreenInteractionPolicy.channelStep(dragDx)
                                    val targetId = step?.let { direction ->
                                        LiveFullscreenInteractionPolicy.adjacentChannelId(
                                            orderedChannelIds = orderedChannelIds,
                                            currentChannelId = currentChannelId,
                                            lastKnownIndex = lastKnownIndex,
                                            step = direction,
                                        )
                                    }
                                    if (targetId != null && step != null) {
                                        val nowMs = SystemClock.elapsedRealtime()
                                        if (nowMs - lastChannelSwitchAtMs >= CHANNEL_SWITCH_DEBOUNCE_MS) {
                                            lastChannelSwitchAtMs = nowMs
                                            channelOverlayForward = step == LiveChannelStep.NEXT
                                            switchedChannelName = channelNameForId(targetId)
                                            onSwitchChannel(targetId)
                                        }
                                    } else {
                                        gestureFeedback = "End of channel list"
                                    }
                                } else if (
                                    axis == LiveFullscreenGestureAxis.VERTICAL &&
                                    !dragStartedOnLeft
                                ) {
                                    onPlayerVolumeCommitted(localVolume)
                                }
                                candidateChannelId = null
                                registerInteraction()
                            },
                            onDrag = { _, dragAmount ->
                                dragDx += dragAmount.x
                                dragDy += dragAmount.y
                                when (
                                    LiveFullscreenInteractionPolicy.classifyDrag(
                                        totalDx = dragDx,
                                        totalDy = dragDy,
                                        minimumDistancePx = directionThresholdPx,
                                    )
                                ) {
                                    LiveFullscreenGestureAxis.HORIZONTAL -> {
                                        val step = LiveFullscreenInteractionPolicy.channelStep(dragDx)
                                        candidateChannelId = step?.let { direction ->
                                            channelOverlayForward = direction == LiveChannelStep.NEXT
                                            LiveFullscreenInteractionPolicy.adjacentChannelId(
                                                orderedChannelIds = orderedChannelIds,
                                                currentChannelId = currentChannelId,
                                                lastKnownIndex = lastKnownIndex,
                                                step = direction,
                                            )
                                        }
                                        gestureFeedback = if (candidateChannelId == null) {
                                            "End of channel list"
                                        } else {
                                            null
                                        }
                                    }

                                    LiveFullscreenGestureAxis.VERTICAL -> {
                                        val height = size.height.coerceAtLeast(1).toFloat()
                                        val delta = -dragDy / height
                                        if (dragStartedOnLeft) {
                                            val next = (dragStartBrightness + delta).coerceIn(0.01f, 1f)
                                            activity?.let { setWindowBrightness(it, next) }
                                            gestureFeedback = "Brightness ${(next * 100f).toInt()}%"
                                        } else {
                                            val next = (dragStartVolume + delta).coerceIn(0f, 1f)
                                            localVolume = next
                                            playbackSessionController.setPlayerVolume(next)
                                            gestureFeedback = "Volume ${(next * 100f).toInt()}%"
                                        }
                                    }

                                    LiveFullscreenGestureAxis.AMBIGUOUS -> Unit
                                }
                            },
                        )
                    },
            ) {
                PlaybackVideoSurface(
                    playbackEngine = playbackEngine,
                    contentMode = contentMode,
                    showFullscreenControls = false,
                    modifier = Modifier.fillMaxSize(),
                )

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(animationSpec = tween(160)) +
                        slideInVertically(animationSpec = tween(180)) { height -> height / 8 },
                    exit = fadeOut(animationSpec = tween(120)) +
                        slideOutVertically(animationSpec = tween(140)) { height -> height / 10 },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        OwnPlayColors.Background.copy(alpha = 0.18f),
                                        OwnPlayColors.Background.copy(alpha = 0.62f),
                                    ),
                                ),
                            )
                            .padding(top = 4.dp),
                    ) {
                        Surface(
                            color = OwnPlayColors.Background.copy(alpha = 0.56f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                guide.now?.let { current ->
                                    Text(
                                        text = "Now • ${fullscreenCurrentProgramLine(current, nowEpochSeconds)}",
                                        color = OwnPlayColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                    FullscreenControlButton(
                                        label = "Record now",
                                        enabled = current.endEpochSeconds?.let { it > nowEpochSeconds } == true,
                                        onClick = {
                                            registerInteraction()
                                            onRecordNow()
                                        },
                                    )
                                }
                                if (guide.now == null) {
                                    FullscreenControlButton(
                                        label = "Record",
                                        enabled = true,
                                        onClick = {
                                            registerInteraction()
                                            onRecordNow()
                                        },
                                    )
                                }
                                guide.next?.let { next ->
                                    Text(
                                        text = "Next ${fullscreenProgramTimeRange(next)} • ${next.title}",
                                        color = OwnPlayColors.TextMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                    FullscreenControlButton(
                                        label = "Schedule",
                                        enabled = next.startEpochSeconds?.let { it > nowEpochSeconds } == true,
                                        onClick = {
                                            registerInteraction()
                                            onScheduleNext()
                                        },
                                    )
                                }
                                if (guide.now == null && guide.next == null) {
                                    Text(
                                        text = "No EPG schedule is available for this channel.",
                                        color = OwnPlayColors.TextMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    FullscreenControlButton(
                                        label = if (contentMode == PlaybackVideoContentMode.FIT) "Fill" else "Fit",
                                        onClick = {
                                            contentMode = if (contentMode == PlaybackVideoContentMode.FIT) {
                                                PlaybackVideoContentMode.FILL
                                            } else {
                                                PlaybackVideoContentMode.FIT
                                            }
                                            registerInteraction()
                                        },
                                    )
                                    FullscreenControlButton(
                                        label = "PiP",
                                        enabled = playbackState.readiness == PlaybackReadiness.PREPARED,
                                        onClick = {
                                            registerInteraction()
                                            onPictureInPicture()
                                        },
                                    )
                                    FullscreenControlButton(
                                        label = "Catch-up",
                                        enabled = catchUpEnabled,
                                        onClick = {
                                            registerInteraction()
                                            onCatchUp()
                                        },
                                    )
                                    FullscreenControlButton(
                                        label = "Back",
                                        onClick = {
                                            registerInteraction()
                                            onDismiss()
                                        },
                                    )
                                    PlaybackTrackControlsOverlay(
                                        state = playbackState,
                                        controller = playbackSessionController,
                                        showTransportControls = false,
                                        onInteraction = ::registerInteraction,
                                        onPanelVisibilityChanged = { open ->
                                            panelOpen = open
                                            if (!open) registerInteraction()
                                        },
                                        surfaceColor = Color.Transparent,
                                        compact = true,
                                        inline = true,
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showPreparingIndicator,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(animationSpec = tween(120)),
                    exit = fadeOut(animationSpec = tween(100)),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(channelName, color = OwnPlayColors.TextPrimary)
                    }
                }

                if (playbackState.readiness == PlaybackReadiness.UNAVAILABLE) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Playback unavailable", color = OwnPlayColors.Error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }

                val channelOverlayLabel = candidateChannelId?.let(channelNameForId) ?: switchedChannelName
                AnimatedVisibility(
                    visible = channelOverlayLabel != null,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(animationSpec = tween(120)) +
                        slideInHorizontally(animationSpec = tween(160)) { width ->
                            if (channelOverlayForward) width / 5 else -width / 5
                        },
                    exit = fadeOut(animationSpec = tween(120)) +
                        slideOutHorizontally(animationSpec = tween(140)) { width ->
                            if (channelOverlayForward) -width / 6 else width / 6
                        },
                ) {
                    Surface(
                        color = OwnPlayColors.Background.copy(alpha = 0.62f),
                        shape = OwnPlayShapes.Medium,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = channelOverlayLabel.orEmpty(),
                            color = OwnPlayColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                gestureFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        color = OwnPlayColors.TextPrimary,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenControlButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = OwnPlayShapes.Small,
        color = OwnPlayColors.Surface.copy(alpha = if (enabled) 0.86f else 0.38f),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) OwnPlayColors.TextPrimary else OwnPlayColors.TextMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private const val CHANNEL_SWITCH_DEBOUNCE_MS = 350L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun currentWindowBrightness(context: Context): Float {
    val activity = context.findActivity()
    val override = activity?.window?.attributes?.screenBrightness
    if (override != null && override >= 0f) return override.coerceIn(0.01f, 1f)
    return runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
        ).coerceIn(1, 255) / 255f
    }.getOrDefault(0.5f)
}

private fun setWindowBrightness(activity: Activity, brightness: Float) {
    val attributes = activity.window.attributes
    attributes.screenBrightness = brightness.coerceIn(0.01f, 1f)
    activity.window.attributes = attributes
}

private fun restoreSystemBrightness(activity: Activity) {
    val attributes = activity.window.attributes
    attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    activity.window.attributes = attributes
}

private fun fullscreenCurrentProgramLine(
    program: LiveProgram,
    nowEpochSeconds: Long,
): String {
    val progress = LiveGuidePolicy.progressFraction(program, nowEpochSeconds)
        ?.let { value -> "${(value * 100).toInt()}%" }
    return listOf(fullscreenProgramTimeRange(program), progress, program.title)
        .filterNotNull()
        .filter(String::isNotBlank)
        .joinToString(" • ")
}

private fun fullscreenProgramTimeRange(program: LiveProgram): String {
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = program.startEpochSeconds?.let { formatter.format(Date(it * 1_000L)) }
    val end = program.endEpochSeconds?.let { formatter.format(Date(it * 1_000L)) }
    return when {
        start != null && end != null -> "$start–$end"
        start != null -> start
        end != null -> end
        else -> ""
    }
}
