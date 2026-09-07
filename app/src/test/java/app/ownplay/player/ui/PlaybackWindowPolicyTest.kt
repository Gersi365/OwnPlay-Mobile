package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWindowPolicyTest {
    @Test
    fun pipEligibilityRequiresSupportPlayingAndVisiblePlaybackSurface() {
        assertTrue(
            PlaybackWindowPolicy.isPipEligible(
                pipSupported = true,
                isPlaying = true,
                playbackSurfaceActive = true,
            ),
        )
        assertFalse(
            PlaybackWindowPolicy.isPipEligible(
                pipSupported = false,
                isPlaying = true,
                playbackSurfaceActive = true,
            ),
        )
        assertFalse(
            PlaybackWindowPolicy.isPipEligible(
                pipSupported = true,
                isPlaying = false,
                playbackSurfaceActive = true,
            ),
        )
        assertFalse(
            PlaybackWindowPolicy.isPipEligible(
                pipSupported = true,
                isPlaying = true,
                playbackSurfaceActive = false,
            ),
        )
        assertFalse(
            PlaybackWindowPolicy.isPipEligible(
                pipSupported = true,
                isPlaying = true,
                playbackSurfaceActive = true,
                pipEnabled = false,
            ),
        )
    }

    @Test
    fun normalApplicationPresentationIsPortraitOnly() {
        assertEquals(
            PlaybackOrientationIntent.PORTRAIT,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                livePreviewActive = false,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun livePreviewFollowsPhysicalSensor() {
        assertEquals(
            PlaybackOrientationIntent.SENSOR,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun fullscreenVideoFollowsPhysicalSensor() {
        assertEquals(
            PlaybackOrientationIntent.SENSOR,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                livePreviewActive = false,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun pictureInPictureReleasesOrientationToSystem() {
        assertEquals(
            PlaybackOrientationIntent.FOLLOW_SYSTEM,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                livePreviewActive = true,
                inPictureInPicture = true,
            ),
        )
    }
}
