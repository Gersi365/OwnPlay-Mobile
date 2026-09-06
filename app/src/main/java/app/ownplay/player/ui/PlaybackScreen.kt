package app.ownplay.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.epg.EpgTimelineProjector
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.PlaybackAudioSelection
import app.ownplay.player.playback.PlaybackNavigationDirection
import app.ownplay.player.playback.PlaybackPresentationPolicy
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.playback.PlaybackSubtitleSelection
import app.ownplay.player.playback.PlaybackTrackState
import app.ownplay.player.playback.PlaybackVideoOutput
import app.ownplay.player.ui.live.LiveFullscreenEpgDirection
import app.ownplay.player.ui.live.LiveFullscreenEpgPolicy
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private const val LIVE_EPG_AUTO_HIDE_MILLIS = 4_500L
private const val LIVE_EPG_PROGRAM_LIMIT = 6
private const val MOBILE_CHANNEL_SWIPE_TRIGGER_FRACTION = 0.12f
private const val MOBILE_GESTURE_FEEDBACK_HIDE_MILLIS = 700L

private enum class MobileFullscreenGestureAxis {
    HORIZONTAL,
    VERTICAL,
}

private data class MobileFullscreenGestureFeedback(
    val label: String,
    val percent: Int,
)

/**
 * Live fullscreen presentation.
 *
 * Live intentionally has no playback menu. The video owns the screen and EPG is the only transient
 * interaction layer. TV remote behavior is CH+ / CH- -> next / previous channel, OK -> reveal EPG,
 * Down -> enter EPG, Left/Right -> move through available programmes, Up -> leave the EPG timeline.
 * D-pad Up/Down are reserved for focus/EPG navigation and never perform direct channel zapping.
 * The final timeline card opens the full guide over Full View without tearing down playback. Mobile
 * uses tap to show/hide EPG, horizontal swipe to change channel, left-side vertical swipe for
 * brightness, and right-side vertical swipe for media volume. Category gestures belong only to the
 * channel browser and never to Full View.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(UnstableApi::class)
@Composable
internal fun PlaybackScreen(
    selection: LivePlaybackSelection,
    state: PlaybackState,
    trackState: PlaybackTrackState,
    videoOutput: PlaybackVideoOutput,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onAudioSelection: (PlaybackAudioSelection) -> Unit,
    onSubtitleSelection: (PlaybackSubtitleSelection) -> Unit,
    onNavigate: (PlaybackNavigationDirection) -> Unit,
    onReturnToChannels: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val hostActivity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val rootFocusRequester = remember { FocusRequester() }
    val touchInteractionSource = remember { MutableInteractionSource() }
    val epgSnapshot by LiveEpgPresentationBridge.snapshot.collectAsState()
    val timeline = remember(epgSnapshot, selection.request.channelId) {
        EpgTimelineProjector.project(
            programs = epgSnapshot?.programs.orEmpty(),
            nowEpochSeconds = System.currentTimeMillis() / 1_000L,
        )
    }
    val overlayPrograms = remember(timeline) {
        buildList {
            timeline.current?.let { add(it) }
            val remaining = (LIVE_EPG_PROGRAM_LIMIT - size).coerceAtLeast(0)
            timeline.future
                .asSequence()
                .filter { program -> program != timeline.current }
                .take(remaining)
                .forEach { program -> add(program) }
        }.distinct()
    }
    val fullGuideIndex = LiveFullscreenEpgPolicy.fullGuideIndex(overlayPrograms.size)
    val currentProgramIndex = overlayPrograms.indexOf(timeline.current).takeIf { it >= 0 } ?: 0

    var epgVisible by remember(selection.request.channelId) { mutableStateOf(true) }
    var epgFocused by remember(selection.request.channelId) { mutableStateOf(false) }
    var epgLoading by remember(selection.request.channelId) { mutableStateOf(true) }
    var showFullGuide by remember(selection.request.channelId) { mutableStateOf(false) }
    var selectedProgramIndex by remember(selection.request.channelId) {
        mutableIntStateOf(currentProgramIndex)
    }
    var interactionGeneration by remember(selection.request.channelId) { mutableIntStateOf(0) }
    var mobileGestureFeedback by remember {
        mutableStateOf<MobileFullscreenGestureFeedback?>(null)
    }
    var mobileGestureFeedbackGeneration by remember { mutableIntStateOf(0) }

    fun revealEpg() {
        epgVisible = true
        interactionGeneration += 1
    }

    fun toggleEpg() {
        epgVisible = !epgVisible
        if (!epgVisible) epgFocused = false
        interactionGeneration += 1
    }

    fun publishMobileGestureFeedback(label: String, percent: Int) {
        mobileGestureFeedback = MobileFullscreenGestureFeedback(
            label = label,
            percent = percent.coerceIn(0, 100),
        )
        mobileGestureFeedbackGeneration += 1
    }

    fun openFullGuide() {
        if (!LiveFullscreenEpgPolicy.canEnterTimeline(overlayPrograms.size)) return
        epgFocused = false
        showFullGuide = true
    }

    FullscreenSystemBarsEffect(enabled = true)

    LaunchedEffect(Unit) {
        onFullscreenStateChanged(true)
    }
    DisposableEffect(onFullscreenStateChanged) {
        onDispose { onFullscreenStateChanged(false) }
    }

    LaunchedEffect(selection.request.sourceId, selection.request.channelId) {
        epgLoading = true
        LiveEpgPresentationBridge.publish(null)
        try {
            val exactSnapshot = LiveEpgPresentationBridge.loadSnapshot(
                sourceId = selection.request.sourceId,
                channelId = selection.request.channelId,
            )
            LiveEpgPresentationBridge.publish(exactSnapshot)
        } finally {
            epgLoading = false
        }
    }

    LaunchedEffect(selection.request.channelId, isTelevision) {
        epgVisible = true
        epgFocused = false
        showFullGuide = false
        selectedProgramIndex = currentProgramIndex
        interactionGeneration += 1
        if (isTelevision) {
            withFrameNanos { }
            rootFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(showFullGuide, isTelevision) {
        if (isTelevision && !showFullGuide) {
            withFrameNanos { }
            rootFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(overlayPrograms, currentProgramIndex) {
        if (!epgFocused) selectedProgramIndex = currentProgramIndex
    }

    LaunchedEffect(epgVisible, epgFocused, interactionGeneration, state, epgLoading, showFullGuide) {
        if (
            epgVisible &&
            !epgFocused &&
            !showFullGuide &&
            !epgLoading &&
            state is PlaybackState.Playing
        ) {
            delay(LIVE_EPG_AUTO_HIDE_MILLIS)
            epgVisible = false
        }
    }

    LaunchedEffect(mobileGestureFeedbackGeneration) {
        if (mobileGestureFeedbackGeneration > 0) {
            delay(MOBILE_GESTURE_FEEDBACK_HIDE_MILLIS)
            mobileGestureFeedback = null
        }
    }

    BackHandler {
        if (showFullGuide) {
            showFullGuide = false
        } else {
            onReturnToChannels()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isTelevision) {
                        Modifier
                            .focusRequester(rootFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if (showFullGuide) return@onPreviewKeyEvent false
                                val native = event.nativeKeyEvent
                                if (native.action != KeyEvent.ACTION_DOWN) {
                                    return@onPreviewKeyEvent false
                                }
                                tvLiveChannelNavigationForKeyCode(native.keyCode)?.let { direction ->
                                    onNavigate(direction)
                                    return@onPreviewKeyEvent true
                                }
                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_BUTTON_A,
                                    KeyEvent.KEYCODE_BUTTON_SELECT,
                                    -> {
                                        when {
                                            !epgVisible -> revealEpg()
                                            epgFocused &&
                                                LiveFullscreenEpgPolicy.isFullGuideSelection(
                                                    selectedIndex = selectedProgramIndex,
                                                    programCount = overlayPrograms.size,
                                                ) -> openFullGuide()
                                            else -> revealEpg()
                                        }
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        if (
                                            !epgVisible ||
                                            epgLoading ||
                                            !LiveFullscreenEpgPolicy.canEnterTimeline(
                                                overlayPrograms.size,
                                            )
                                        ) {
                                            false
                                        } else {
                                            epgFocused = true
                                            selectedProgramIndex = selectedProgramIndex.coerceIn(
                                                0,
                                                fullGuideIndex,
                                            )
                                            interactionGeneration += 1
                                            true
                                        }
                                    }

                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        if (epgFocused) {
                                            epgFocused = false
                                            interactionGeneration += 1
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        if (epgFocused) {
                                            selectedProgramIndex =
                                                LiveFullscreenEpgPolicy.moveSelection(
                                                    currentIndex = selectedProgramIndex,
                                                    direction = LiveFullscreenEpgDirection.LEFT,
                                                    programCount = overlayPrograms.size,
                                                )
                                            interactionGeneration += 1
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (epgFocused) {
                                            selectedProgramIndex =
                                                LiveFullscreenEpgPolicy.moveSelection(
                                                    currentIndex = selectedProgramIndex,
                                                    direction = LiveFullscreenEpgDirection.RIGHT,
                                                    programCount = overlayPrograms.size,
                                                )
                                            interactionGeneration += 1
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    else -> false
                                }
                            }
                            .focusable()
                    } else {
                        Modifier
                    },
                ),
        ) {
            LiveFullscreenVideoSurface(
                videoOutput = videoOutput,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        isTelevision,
                        selection.request.channelId,
                        hostActivity,
                        audioManager,
                        showFullGuide,
                    ) {
                        if (isTelevision || showFullGuide) return@pointerInput

                        var startX = 0f
                        var totalX = 0f
                        var totalY = 0f
                        var gestureAxis: MobileFullscreenGestureAxis? = null
                        var initialBrightness = currentWindowBrightness(hostActivity, context)
                        var initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val maximumVolume =
                            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

                        detectDragGestures(
                            onDragStart = { offset ->
                                startX = offset.x
                                totalX = 0f
                                totalY = 0f
                                gestureAxis = null
                                initialBrightness = currentWindowBrightness(hostActivity, context)
                                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            },
                            onDrag = { change, dragAmount ->
                                totalX += dragAmount.x
                                totalY += dragAmount.y

                                if (gestureAxis == null) {
                                    gestureAxis = if (abs(totalX) >= abs(totalY)) {
                                        MobileFullscreenGestureAxis.HORIZONTAL
                                    } else {
                                        MobileFullscreenGestureAxis.VERTICAL
                                    }
                                }

                                when (gestureAxis) {
                                    MobileFullscreenGestureAxis.HORIZONTAL -> {
                                        change.consume()
                                    }

                                    MobileFullscreenGestureAxis.VERTICAL -> {
                                        change.consume()
                                        val height = size.height.toFloat().coerceAtLeast(1f)
                                        val normalizedDelta = (-totalY / height).coerceIn(-1f, 1f)
                                        if (startX < size.width / 2f) {
                                            val brightness =
                                                (initialBrightness + normalizedDelta).coerceIn(0.01f, 1f)
                                            setWindowBrightness(hostActivity, brightness)
                                            publishMobileGestureFeedback(
                                                label = "Brightness",
                                                percent = (brightness * 100f).roundToInt(),
                                            )
                                        } else {
                                            val volume = (
                                                initialVolume +
                                                    normalizedDelta * maximumVolume
                                                ).roundToInt().coerceIn(0, maximumVolume)
                                            audioManager.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                volume,
                                                0,
                                            )
                                            publishMobileGestureFeedback(
                                                label = "Volume",
                                                percent = (volume * 100f / maximumVolume).roundToInt(),
                                            )
                                        }
                                    }

                                    null -> Unit
                                }
                            },
                            onDragEnd = {
                                if (gestureAxis == MobileFullscreenGestureAxis.HORIZONTAL) {
                                    val triggerDistance = max(
                                        viewConfiguration.touchSlop * 4f,
                                        size.width * MOBILE_CHANNEL_SWIPE_TRIGGER_FRACTION,
                                    )
                                    if (abs(totalX) >= triggerDistance) {
                                        onNavigate(
                                            if (totalX < 0f) {
                                                PlaybackNavigationDirection.NEXT
                                            } else {
                                                PlaybackNavigationDirection.PREVIOUS
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    .clickable(
                        enabled = !isTelevision && !showFullGuide,
                        interactionSource = touchInteractionSource,
                        indication = null,
                        onClick = ::toggleEpg,
                    ),
            )

            val controls = PlaybackPresentationPolicy.controlsFor(state)
            if (controls.showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(34.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (state is PlaybackState.Failed) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = playbackStatusLabel(state),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            mobileGestureFeedback?.let { feedback ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.76f),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = "${feedback.label} · ${feedback.percent}%",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }

            AnimatedVisibility(
                visible = epgVisible && !showFullGuide,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LiveFullscreenEpgOverlay(
                    channelName = selection.displayName,
                    programs = overlayPrograms,
                    currentProgram = timeline.current,
                    loading = epgLoading,
                    selectedIndex = selectedProgramIndex,
                    focused = epgFocused,
                    fullGuideIndex = fullGuideIndex,
                    onProgramSelected = { index ->
                        selectedProgramIndex = index
                        epgFocused = true
                        revealEpg()
                    },
                    onFullGuide = ::openFullGuide,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showFullGuide) {
        EpgGuideSheet(
            channelName = selection.displayName,
            snapshot = epgSnapshot,
            loading = epgLoading,
            failed = false,
            onDismiss = { showFullGuide = false },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LiveFullscreenVideoSurface(
    videoOutput: PlaybackVideoOutput,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(AndroidColor.BLACK)
                videoOutput.bind(this)
            }
        },
        modifier = modifier,
        update = { view ->
            view.useController = false
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { view -> videoOutput.unbind(view) },
    )
}

@Composable
private fun LiveFullscreenEpgOverlay(
    channelName: String,
    programs: List<EpgProgram>,
    currentProgram: EpgProgram?,
    loading: Boolean,
    selectedIndex: Int,
    focused: Boolean,
    fullGuideIndex: Int,
    onProgramSelected: (Int) -> Unit,
    onFullGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex, focused, programs.size) {
        if (focused && programs.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex.coerceIn(0, fullGuideIndex))
        }
    }

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.82f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (focused) {
                            "EPG · ← → browse · ↑ video"
                        } else {
                            "EPG · ↓ browse"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 1,
                    )
                }
            }

            when {
                loading -> Text(
                    text = "Loading EPG…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                )
                programs.isEmpty() -> Text(
                    text = "EPG unavailable for this channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.76f),
                )
                else -> LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(programs) { index, program ->
                        LiveFullscreenProgramCard(
                            program = program,
                            selected = focused && selectedIndex == index,
                            current = program == currentProgram,
                            onClick = { onProgramSelected(index) },
                        )
                    }
                    item(key = "full-epg") {
                        LiveFullscreenFullGuideCard(
                            selected = focused && selectedIndex == fullGuideIndex,
                            onClick = onFullGuide,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveFullscreenProgramCard(
    program: EpgProgram,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val emphasized = selected || current
    Surface(
        modifier = Modifier
            .width(224.dp)
            .heightIn(min = 76.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            current -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (current) "NOW · ${timeRange(program)}" else timeRange(program),
                style = MaterialTheme.typography.labelSmall,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (emphasized) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LiveFullscreenFullGuideCard(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .width(160.dp)
            .heightIn(min = 76.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "FULL EPG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Open guide  →",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "Stays in Full View",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun timeRange(program: EpgProgram): String = when {
    program.startLabel != null && program.endLabel != null ->
        "${program.startLabel}–${program.endLabel}"
    program.startLabel != null -> program.startLabel
    else -> "—"
}

internal fun tvLiveChannelNavigationForKeyCode(keyCode: Int): PlaybackNavigationDirection? =
    when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> PlaybackNavigationDirection.NEXT
        KeyEvent.KEYCODE_CHANNEL_DOWN -> PlaybackNavigationDirection.PREVIOUS
        else -> null
    }

private fun currentWindowBrightness(activity: Activity?, context: Context): Float {
    val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
    if (windowBrightness >= 0f) return windowBrightness.coerceIn(0.01f, 1f)

    val systemBrightness = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (systemBrightness / 255f).coerceIn(0.01f, 1f)
}

private fun setWindowBrightness(activity: Activity?, brightness: Float) {
    val host = activity ?: return
    val attributes = host.window.attributes
    attributes.screenBrightness = brightness.coerceIn(0.01f, 1f)
    host.window.attributes = attributes
}

@Composable
private fun FullscreenSystemBarsEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val activity = context.findActivity()
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
