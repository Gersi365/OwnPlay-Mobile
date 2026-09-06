package app.ownplay.player.playback

internal enum class LiveActivityBackgroundAction {
    NONE,
    SUSPEND_AND_RETAIN_SURFACE,
}

/**
 * Activity-level background policy for OwnPlay Mobile.
 *
 * Active Live, Movie, and Series playback suspends outside PiP/configuration changes so the
 * existing PlaybackController can retain a recoverable session and resume position.
 */
internal object LiveActivityLifecyclePolicy {
    fun backgroundAction(
        state: PlaybackState,
        inPictureInPicture: Boolean,
        changingConfigurations: Boolean,
    ): LiveActivityBackgroundAction {
        if (inPictureInPicture || changingConfigurations) {
            return LiveActivityBackgroundAction.NONE
        }

        val mediaKind = when (state) {
            is PlaybackState.Loading -> state.request.mediaKind
            is PlaybackState.Playing -> state.request.mediaKind
            is PlaybackState.Paused -> state.request.mediaKind
            PlaybackState.Idle,
            is PlaybackState.Failed,
            -> null
        }
        val shouldSuspend = when (mediaKind) {
            PlaybackMediaKind.LIVE -> true
            PlaybackMediaKind.MOVIE,
            PlaybackMediaKind.SERIES_EPISODE,
            -> true
            null -> false
        }
        return if (shouldSuspend) {
            LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE
        } else {
            LiveActivityBackgroundAction.NONE
        }
    }
}
