package app.ownplay.player.ui.tv

import app.ownplay.player.playback.PlaybackState

internal enum class TvBackgroundPlaybackAction {
    NONE,
    SUSPEND,
}

internal object TvPlaybackLifecyclePolicy {
    fun backgroundAction(state: PlaybackState): TvBackgroundPlaybackAction = when (state) {
        PlaybackState.Idle,
        is PlaybackState.Failed,
        -> TvBackgroundPlaybackAction.NONE

        is PlaybackState.Loading,
        is PlaybackState.Playing,
        is PlaybackState.Paused,
        -> TvBackgroundPlaybackAction.SUSPEND
    }
}
