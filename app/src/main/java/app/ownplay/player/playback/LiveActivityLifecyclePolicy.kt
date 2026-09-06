package app.ownplay.player.playback

import app.ownplay.player.BuildConfig

internal enum class LiveActivityBackgroundAction {
    NONE,
    SUSPEND_AND_RETAIN_SURFACE,
}

/**
 * Activity-level background policy used by the shared MainActivity.
 *
 * Live suspends outside PiP/configuration changes on both targets. Mobile additionally suspends
 * long-form Movie/Series playback so the existing PlaybackController can retain resume position.
 * TV non-Live playback remains delegated to TvPlaybackLifecyclePolicy.
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
            -> !BuildConfig.IS_TV_BUILD
            null -> false
        }
        return if (shouldSuspend) {
            LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE
        } else {
            LiveActivityBackgroundAction.NONE
        }
    }
}
