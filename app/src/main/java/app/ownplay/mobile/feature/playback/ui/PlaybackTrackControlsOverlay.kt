package app.ownplay.mobile.feature.playback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionState
import app.ownplay.mobile.feature.playback.domain.PlaybackSpeedPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackTrackOption
import app.ownplay.mobile.feature.playback.domain.PlaybackTrackSelectionIssue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackTrackControlsOverlay(
    state: PlaybackSessionState,
    controller: PlaybackSessionController,
    showTransportControls: Boolean = true,
    showSpeedControl: Boolean = false,
    extraControlLabel: String? = null,
    extraControlEnabled: Boolean = false,
    onExtraControl: () -> Unit = {},
    onInteraction: () -> Unit = {},
    onPanelVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var audioSheetOpen by remember { mutableStateOf(false) }
    var subtitleSheetOpen by remember { mutableStateOf(false) }
    var speedSheetOpen by remember { mutableStateOf(false) }

    val audioTracks = state.tracks.audioTracks
    val subtitleTracks = state.tracks.subtitleTracks
    val issueMessage = when (state.tracks.selectionIssue) {
        PlaybackTrackSelectionIssue.AUDIO_UNSUPPORTED -> "This audio track is not supported."
        PlaybackTrackSelectionIssue.SUBTITLE_UNSUPPORTED -> "This subtitle track is not supported."
        PlaybackTrackSelectionIssue.SELECTION_FAILED -> "Track selection failed."
        null -> null
    }

    Surface(
        color = OwnPlayColors.Surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showTransportControls) {
                    TextButton(
                        enabled = state.readiness != PlaybackReadiness.UNAVAILABLE,
                        onClick = {
                            onInteraction()
                            if (state.playWhenReady) controller.pause() else controller.play()
                        },
                    ) {
                        Text(if (state.playWhenReady) "Pause" else "Play")
                    }
                }
                if (audioTracks.size >= 2) {
                    TextButton(
                        onClick = {
                            onInteraction()
                            audioSheetOpen = true
                            onPanelVisibilityChanged(true)
                        },
                    ) {
                        Text("Audio")
                    }
                }
                if (subtitleTracks.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            onInteraction()
                            subtitleSheetOpen = true
                            onPanelVisibilityChanged(true)
                        },
                    ) {
                        Text("Subtitles")
                    }
                }
                if (showSpeedControl) {
                    TextButton(
                        onClick = {
                            onInteraction()
                            speedSheetOpen = true
                            onPanelVisibilityChanged(true)
                        },
                    ) {
                        Text(
                            if (state.speed == PlaybackSpeedPolicy.DEFAULT_SPEED) {
                                "Speed"
                            } else {
                                "Speed ${playbackSpeedLabel(state.speed)}"
                            },
                        )
                    }
                }
                extraControlLabel?.let { label ->
                    TextButton(
                        enabled = extraControlEnabled,
                        onClick = {
                            onInteraction()
                            onExtraControl()
                        },
                    ) {
                        Text(label)
                    }
                }
            }
            issueMessage?.let { message ->
                Text(text = message, color = OwnPlayColors.Error)
            }
        }
    }

    if (audioSheetOpen) {
        TrackSelectionSheet(
            title = "Audio",
            firstOptionLabel = "Automatic",
            firstOptionSelected = state.tracks.selectedAudioTrackId == null,
            tracks = audioTracks,
            onFirstOptionSelected = {
                if (controller.selectAudioTrack(null)) {
                    audioSheetOpen = false
                    onPanelVisibilityChanged(false)
                    onInteraction()
                }
            },
            onTrackSelected = { trackId ->
                if (controller.selectAudioTrack(trackId)) {
                    audioSheetOpen = false
                    onPanelVisibilityChanged(false)
                    onInteraction()
                }
            },
            onDismiss = {
                audioSheetOpen = false
                onPanelVisibilityChanged(false)
                onInteraction()
            },
        )
    }

    if (subtitleSheetOpen) {
        TrackSelectionSheet(
            title = "Subtitles",
            firstOptionLabel = "Off",
            firstOptionSelected = state.tracks.selectedSubtitleTrackId == null,
            tracks = subtitleTracks,
            onFirstOptionSelected = {
                if (controller.selectSubtitleTrack(null)) {
                    subtitleSheetOpen = false
                    onPanelVisibilityChanged(false)
                    onInteraction()
                }
            },
            onTrackSelected = { trackId ->
                if (controller.selectSubtitleTrack(trackId)) {
                    subtitleSheetOpen = false
                    onPanelVisibilityChanged(false)
                    onInteraction()
                }
            },
            onDismiss = {
                subtitleSheetOpen = false
                onPanelVisibilityChanged(false)
                onInteraction()
            },
        )
    }

    if (speedSheetOpen) {
        PlaybackSpeedSheet(
            selectedSpeed = state.speed,
            onSpeedSelected = { speed ->
                if (controller.setSpeed(speed)) {
                    speedSheetOpen = false
                    onPanelVisibilityChanged(false)
                    onInteraction()
                }
            },
            onDismiss = {
                speedSheetOpen = false
                onPanelVisibilityChanged(false)
                onInteraction()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSpeedSheet(
    selectedSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Speed",
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            PlaybackSpeedPolicy.supportedSpeeds.forEach { speed ->
                TextButton(
                    onClick = { onSpeedSelected(speed) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (speed == selectedSpeed) {
                            "${playbackSpeedLabel(speed)} • Selected"
                        } else {
                            playbackSpeedLabel(speed)
                        },
                    )
                }
            }
        }
    }
}

private fun playbackSpeedLabel(speed: Float): String =
    when (speed) {
        0.5f -> "0.5×"
        0.75f -> "0.75×"
        1f -> "1×"
        1.25f -> "1.25×"
        1.5f -> "1.5×"
        2f -> "2×"
        else -> "${speed}×"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSelectionSheet(
    title: String,
    firstOptionLabel: String,
    firstOptionSelected: Boolean,
    tracks: List<PlaybackTrackOption>,
    onFirstOptionSelected: () -> Unit,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item(key = "default") {
                    TextButton(
                        onClick = onFirstOptionSelected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (firstOptionSelected) {
                                "$firstOptionLabel • Selected"
                            } else {
                                firstOptionLabel
                            },
                        )
                    }
                }
                items(tracks, key = { it.id }) { track ->
                    TextButton(
                        onClick = { onTrackSelected(track.id) },
                        enabled = track.supported,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = track.displayLabel(),
                            color = if (track.supported) {
                                OwnPlayColors.TextPrimary
                            } else {
                                OwnPlayColors.TextMuted
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun PlaybackTrackOption.displayLabel(): String = when {
    selected -> "$label • Selected"
    !supported -> "$label • Unsupported"
    else -> label
}
