package app.ownplay.player.ui

import app.ownplay.player.playback.PlaybackFailureCategory
import app.ownplay.player.playback.PlaybackState

internal fun playbackStatusLabel(state: PlaybackState): String = when (state) {
    PlaybackState.Idle -> "Idle"
    is PlaybackState.Loading -> "Starting playback…"
    is PlaybackState.Playing -> if (state.buffering) "Buffering…" else "Playing"
    is PlaybackState.Paused -> "Paused"
    is PlaybackState.Failed -> when (state.failure.category) {
        PlaybackFailureCategory.NETWORK_UNAVAILABLE -> "Network unavailable"
        PlaybackFailureCategory.TIMEOUT -> "Playback timed out"
        PlaybackFailureCategory.AUTHENTICATION_FAILURE -> "Authentication failed"
        PlaybackFailureCategory.STREAM_UNAVAILABLE -> "Stream unavailable"
        PlaybackFailureCategory.UNSUPPORTED_MEDIA -> "Unsupported media"
        PlaybackFailureCategory.UNKNOWN -> "Playback failed"
    }
}
