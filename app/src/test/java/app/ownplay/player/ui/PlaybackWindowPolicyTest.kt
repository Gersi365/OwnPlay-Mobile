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
    fun normalAppShellIsAlwaysPortrait() {
        listOf(
            AppOrientationMode.PORTRAIT,
            AppOrientationMode.LANDSCAPE,
        ).forEach { storedMode ->
            assertEquals(
                PlaybackOrientationIntent.PORTRAIT,
                PlaybackWindowPolicy.orientationIntent(
                    fullscreen = false,
                    appOrientation = storedMode,
                    inPictureInPicture = false,
                ),
            )
        }
    }

    @Test
    fun livePreviewRotationRequestCannotRotateBrowsingShell() {
        assertEquals(
            PlaybackOrientationIntent.PORTRAIT,
            PlaybackWindowPolicy.orientationIntent(
                fullscreen = false,
                appOrientation = AppOrientationMode.LANDSCAPE,
                inPictureInPicture = false,
                livePreviewRotationEnabled = true,
            ),
        )
    }

    @Test
    fun fullscreenFollowsPhysicalSensorByDefault() {
        listOf(
            AppOrientationMode.PORTRAIT,
            AppOrientationMode.LANDSCAPE,
        ).forEach { storedMode ->
            assertEquals(
                PlaybackOrientationIntent.SENSOR,
                PlaybackWindowPolicy.orientationIntent(
                    fullscreen = true,
                    appOrientation = storedMode,
                    inPictureInPicture = false,
                ),
            )
        }
    }

    @Test
    fun fullscreenFallsBackToPortraitWhenSensorRotationIsDisabled() {
        assertEquals(
            PlaybackOrientationIntent.PORTRAIT,
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
