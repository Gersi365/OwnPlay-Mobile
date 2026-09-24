package app.ownplay.mobile.feature.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.feature.playback.ui.FullscreenPlaybackWindow
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.feature.live.domain.LiveCatchUpCatalog
import app.ownplay.mobile.feature.live.domain.LiveCatchUpPolicy
import app.ownplay.mobile.feature.live.domain.LiveCatchUpProgram
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionState
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.ui.PlaybackTrackControlsOverlay
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoContentMode
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoSurface
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiveCatchUpList(
    catalog: LiveCatchUpCatalog,
    listState: LazyListState,
    onProgramSelected: (LiveCatchUpProgram) -> Unit,
    onBackToLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(catalog.programs) {
        catalog.programs.groupBy { program ->
            DateFormat.getDateInstance(DateFormat.MEDIUM)
                .format(Date(program.startEpochSeconds * 1_000L))
        }.entries.toList()
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Catch-up",
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onBackToLive) { Text("Back to Live") }
        }

        if (!catalog.supported) {
            Text(
                text = "Catch-up is not available for this channel.",
                color = OwnPlayColors.TextMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }

        if (catalog.programs.isEmpty()) {
            Text(
                text = "No catch-up programs are available.",
                color = OwnPlayColors.TextMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            groups.forEach { (day, programs) ->
                stickyHeader(key = "catch-up-day:$day") {
                    Surface(
                        color = OwnPlayColors.Background,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = day,
                            color = OwnPlayColors.TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(
                    count = programs.size,
                    key = { index -> programs[index].programId },
                ) { index ->
                    val program = programs[index]
                    Surface(
                        color = OwnPlayColors.Surface,
                        onClick = { onProgramSelected(program) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = catchUpProgramLine(program),
                                color = OwnPlayColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            LiveCatchUpPolicy.progressFraction(program)?.let { progress ->
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatchUpPreviewCard(
    target: PlaybackTarget.CatchUp,
    readiness: PlaybackReadiness,
    playbackEngine: Media3PlaybackEngine,
    onFullscreen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = OwnPlayColors.Surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            PlaybackVideoSurface(
                playbackEngine = playbackEngine,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.title,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        text = catchUpTargetTimeRange(target),
                        color = OwnPlayColors.TextSecondary,
                    )
                    if (readiness == PlaybackReadiness.PREPARING) {
                        Text("Preparing catch-up…", color = OwnPlayColors.TextMuted)
                    } else if (readiness == PlaybackReadiness.UNAVAILABLE) {
                        Text("Catch-up playback unavailable", color = OwnPlayColors.Error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                TextButton(
                    enabled = readiness != PlaybackReadiness.UNAVAILABLE,
                    onClick = onFullscreen,
                ) { Text("Fullscreen") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatchUpResumeSheet(
    program: LiveCatchUpProgram,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = program.title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onResume) { Text("Resume") }
            TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }
}

@Composable
internal fun CatchUpFullscreenPresentation(
    target: PlaybackTarget.CatchUp,
    playbackState: PlaybackSessionState,
    playbackSessionController: PlaybackSessionController,
    playbackEngine: Media3PlaybackEngine,
    onPictureInPicture: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var contentMode by remember(target.programId) { mutableStateOf(PlaybackVideoContentMode.FIT) }
    var controlsVisible by remember(target.programId) { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var interactionRevision by remember { mutableIntStateOf(0) }
    var positionMs by remember(target.programId) { mutableLongStateOf(0L) }
    var durationMs by remember(target.programId) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    fun registerInteraction() {
        controlsVisible = true
        interactionRevision += 1
    }

    fun seekRelative(deltaMs: Long) {
        val snapshot = playbackSessionController.positionSnapshot() ?: return
        val duration = snapshot.durationMs?.takeIf { it > 0L } ?: return
        val targetMs = (snapshot.positionMs + deltaMs).coerceIn(0L, duration)
        if (playbackSessionController.seekTo(targetMs)) {
            positionMs = targetMs
            durationMs = duration
            seekFeedback = if (deltaMs < 0) "−10 sec" else "+10 sec"
            registerInteraction()
        }
    }

    LaunchedEffect(target.programId, playbackState.readiness, playbackState.playWhenReady) {
        while (true) {
            val snapshot = playbackSessionController.positionSnapshot()
            if (!dragging && snapshot != null) {
                positionMs = snapshot.positionMs
                durationMs = snapshot.durationMs ?: 0L
            }
            delay(500L)
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(900L)
            seekFeedback = null
        }
    }
    LaunchedEffect(
        playbackState.playWhenReady,
        controlsVisible,
        panelOpen,
        interactionRevision,
    ) {
        if (playbackState.playWhenReady && controlsVisible && !panelOpen) {
            delay(3_000L)
            controlsVisible = false
        }
    }

    BackHandler(onBack = onDismiss)

    FullscreenPlaybackWindow(onDismissRequest = onDismiss) {
        Surface(
            color = OwnPlayColors.Background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(target.programId) {
                        detectTapGestures(
                            onTap = { registerInteraction() },
                            onDoubleTap = { offset ->
                                if (offset.x < size.width / 2f) {
                                    seekRelative(-10_000L)
                                } else {
                                    seekRelative(10_000L)
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

                if (controlsVisible || !playbackState.playWhenReady) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = target.title,
                            color = OwnPlayColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = catchUpTargetTimeRange(target),
                            color = OwnPlayColors.TextSecondary,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                contentMode = PlaybackVideoContentMode.FIT
                                registerInteraction()
                            },
                        ) { Text(if (contentMode == PlaybackVideoContentMode.FIT) "Fit ✓" else "Fit") }
                        TextButton(
                            onClick = {
                                contentMode = PlaybackVideoContentMode.FILL
                                registerInteraction()
                            },
                        ) { Text(if (contentMode == PlaybackVideoContentMode.FILL) "Fill ✓" else "Fill") }
                        TextButton(
                            enabled = playbackState.readiness == PlaybackReadiness.PREPARED,
                            onClick = {
                                registerInteraction()
                                onPictureInPicture()
                            },
                        ) { Text("PiP") }
                        TextButton(onClick = onDismiss) { Text("Collapse") }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        if (durationMs > 0L) {
                            val shownPosition = if (dragging) {
                                (dragFraction * durationMs).toLong()
                            } else {
                                positionMs.coerceIn(0L, durationMs)
                            }
                            Slider(
                                value = if (durationMs > 0L) {
                                    shownPosition.toFloat() / durationMs.toFloat()
                                } else {
                                    0f
                                },
                                onValueChange = { value ->
                                    dragging = true
                                    dragFraction = value.coerceIn(0f, 1f)
                                    registerInteraction()
                                },
                                onValueChangeFinished = {
                                    val requested = (dragFraction * durationMs).toLong()
                                    playbackSessionController.seekTo(requested)
                                    positionMs = requested
                                    dragging = false
                                    registerInteraction()
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Text(
                                text = "${formatDuration(shownPosition)} / ${formatDuration(durationMs)}",
                                color = OwnPlayColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        PlaybackTrackControlsOverlay(
                            state = playbackState,
                            controller = playbackSessionController,
                            showTransportControls = true,
                            onInteraction = ::registerInteraction,
                            onPanelVisibilityChanged = { open ->
                                panelOpen = open
                                if (!open) registerInteraction()
                            },
                        )
                    }
                }

                if (playbackState.readiness == PlaybackReadiness.PREPARING) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                if (playbackState.readiness == PlaybackReadiness.UNAVAILABLE) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Catch-up playback unavailable", color = OwnPlayColors.Error)
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                seekFeedback?.let { feedback ->
                    Text(
                        text = feedback,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

private fun catchUpProgramLine(program: LiveCatchUpProgram): String {
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = formatter.format(Date(program.startEpochSeconds * 1_000L))
    return "$start • ${program.title}"
}

private fun catchUpTargetTimeRange(target: PlaybackTarget.CatchUp): String {
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = formatter.format(Date(target.startEpochSeconds * 1_000L))
    val end = formatter.format(Date(target.endEpochSeconds * 1_000L))
    return "$start–$end"
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
