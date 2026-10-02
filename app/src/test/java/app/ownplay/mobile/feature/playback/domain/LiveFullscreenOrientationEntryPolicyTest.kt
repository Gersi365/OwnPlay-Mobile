package app.ownplay.mobile.feature.playback.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFullscreenOrientationEntryPolicyTest {
    @Test
    fun landscapeDegreeRangesMatchPhysicalLandscape() {
        assertTrue(LiveFullscreenOrientationEntryPolicy.isLandscapeOrientation(90))
        assertTrue(LiveFullscreenOrientationEntryPolicy.isLandscapeOrientation(270))
        assertFalse(LiveFullscreenOrientationEntryPolicy.isLandscapeOrientation(0))
        assertFalse(LiveFullscreenOrientationEntryPolicy.isLandscapeOrientation(180))
    }

    @Test
    fun portraitToLandscapeLivePreviewEntersFullscreen() {
        assertTrue(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                wasLandscape = false,
                isLandscape = true,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun repeatedLandscapeSampleDoesNotReenterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                wasLandscape = true,
                isLandscape = true,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun firstUnknownOrientationHistoryDoesNotEnterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                wasLandscape = null,
                isLandscape = true,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun nonLivePreviewDoesNotEnterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                wasLandscape = false,
                isLandscape = true,
                livePreviewActive = false,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun pictureInPictureDoesNotEnterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                wasLandscape = false,
                isLandscape = true,
                livePreviewActive = true,
                inPictureInPicture = true,
            ),
        )
    }
}
