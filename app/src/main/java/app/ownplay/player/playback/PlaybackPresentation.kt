package app.ownplay.player.playback

enum class PlaybackResizeMode {
    FIT,
    FILL,
    ZOOM;

    fun next(): PlaybackResizeMode = when (this) {
        FIT -> FILL
        FILL -> ZOOM
        ZOOM -> FIT
    }
}

data class PlaybackControlAvailability(
    val showLoading: Boolean,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canRetry: Boolean,
)

object PlaybackPresentationPolicy {
    fun controlsFor(state: PlaybackState): PlaybackControlAvailability = when (state) {
        PlaybackState.Idle -> PlaybackControlAvailability(
            showLoading = false,
            canPlay = false,
            canPause = false,
            canRetry = false,
        )
        is PlaybackState.Loading -> PlaybackControlAvailability(
            showLoading = true,
            canPlay = false,
            canPause = false,
            canRetry = false,
        )
        is PlaybackState.Playing -> PlaybackControlAvailability(
            showLoading = state.buffering,
            canPlay = false,
            canPause = true,
            canRetry = false,
        )
        is PlaybackState.Paused -> PlaybackControlAvailability(
            showLoading = false,
            canPlay = true,
            canPause = false,
            canRetry = false,
        )
        is PlaybackState.Failed -> PlaybackControlAvailability(
            showLoading = false,
            canPlay = false,
            canPause = false,
            canRetry = state.failure.retryable,
        )
    }
}
