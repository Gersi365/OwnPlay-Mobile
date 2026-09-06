package app.ownplay.player.ui

import app.ownplay.player.personalization.AppOrientationMode
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
    fun portraitSettingLocksNormalAppShellToPortrait() {
        assertEquals(
            PlaybackOrientationIntent.PORTRAIT,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                appOrientation = AppOrientationMode.PORTRAIT,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun landscapeSettingLocksNormalAppShellToLandscape() {
        assertEquals(
            PlaybackOrientationIntent.LANDSCAPE,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                appOrientation = AppOrientationMode.LANDSCAPE,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun livePreviewCanTemporarilyFollowPhysicalSensor() {
        assertEquals(
            PlaybackOrientationIntent.SENSOR,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                appOrientation = AppOrientationMode.PORTRAIT,
                inPictureInPicture = false,
                livePreviewRotationEnabled = true,
            ),
        )
    }

    @Test
    fun fullscreenAlwaysFollowsPhysicalSensorByDefault() {
        assertEquals(
            PlaybackOrientationIntent.SENSOR,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                appOrientation = AppOrientationMode.PORTRAIT,
                inPictureInPicture = false,
            ),
        )
        assertEquals(
            PlaybackOrientationIntent.SENSOR,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                appOrientation = AppOrientationMode.LANDSCAPE,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun fullscreenKeepsLandscapeWhenSensorRotationIsDisabled() {
        assertEquals(
            PlaybackOrientationIntent.LANDSCAPE,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                appOrientation = AppOrientationMode.LANDSCAPE,
                inPictureInPicture = false,
                fullscreenSensorRotationEnabled = false,
            ),
        )
    }

    @Test
    fun pictureInPictureReleasesOrientationToSystem() {
        assertEquals(
            PlaybackOrientationIntent.FOLLOW_SYSTEM,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = true,
                appOrientation = AppOrientationMode.LANDSCAPE,
                inPictureInPicture = true,
                fullscreenSensorRotationEnabled = false,
                livePreviewRotationEnabled = true,
            ),
        )
    }
}
