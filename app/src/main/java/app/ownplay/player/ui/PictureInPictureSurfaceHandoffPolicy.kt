package app.ownplay.player.ui

import app.ownplay.player.playback.PlaybackMediaKind

internal enum class PictureInPictureSurfaceBindingMode {
    MEDIA3_TRANSFER,
    DETACH_BEFORE_BIND,
}

/**
 * Live Preview uses Media3's atomic PlayerView target transfer when entering/leaving PiP so the
 * decoded video never passes through an intentional no-surface gap. Live Full View retains the
 * established detach-before-bind path that has already behaved correctly in physical QA.
 * VOD/Series keep the existing Media3 transfer behavior.
 */
internal object PictureInPictureSurfaceHandoffPolicy {
    fun modeFor(
        mediaKind: PlaybackMediaKind?,
        liveWasFullscreen: Boolean = true,
    ): PictureInPictureSurfaceBindingMode =
        if (mediaKind == PlaybackMediaKind.LIVE && liveWasFullscreen) {
            PictureInPictureSurfaceBindingMode.DETACH_BEFORE_BIND
        } else {
            PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER
        }

    fun handoff(
        mode: PictureInPictureSurfaceBindingMode,
        detachCurrentSurface: () -> Unit,
        bindDestinationSurface: () -> Unit,
    ) {
        if (mode == PictureInPictureSurfaceBindingMode.DETACH_BEFORE_BIND) {
            detachCurrentSurface()
        }
        bindDestinationSurface()
    }
}
