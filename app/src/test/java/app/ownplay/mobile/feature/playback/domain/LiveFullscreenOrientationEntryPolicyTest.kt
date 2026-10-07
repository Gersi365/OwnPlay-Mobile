package app.ownplay.mobile.feature.playback.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFullscreenOrientationEntryPolicyTest {
    @Test
    fun physicalOrientationUsesStablePortraitLandscapeAndNeutralTransitionBands() {
        assertEquals(
            LivePhysicalOrientation.PORTRAIT,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(0),
        )
        assertEquals(
            LivePhysicalOrientation.PORTRAIT,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(180),
        )
        assertEquals(
            LivePhysicalOrientation.LANDSCAPE,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(90),
        )
        assertEquals(
            LivePhysicalOrientation.LANDSCAPE,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(270),
        )
        assertEquals(
            LivePhysicalOrientation.TRANSITION,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(45),
        )
        assertEquals(
            LivePhysicalOrientation.TRANSITION,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(135),
        )
        assertEquals(
            LivePhysicalOrientation.TRANSITION,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(225),
        )
        assertEquals(
            LivePhysicalOrientation.TRANSITION,
            LiveFullscreenOrientationEntryPolicy.classifyPhysicalOrientation(315),
        )
    }

    @Test
    fun portraitToLandscapeLivePreviewEntersFullscreen() {
        assertTrue(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                previousStableOrientation = LivePhysicalOrientation.PORTRAIT,
                currentOrientation = LivePhysicalOrientation.LANDSCAPE,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun transitionSamplesDoNotEnterOrExitFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                previousStableOrientation = LivePhysicalOrientation.PORTRAIT,
                currentOrientation = LivePhysicalOrientation.TRANSITION,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                currentOrientation = LivePhysicalOrientation.TRANSITION,
                liveFullscreenActive = true,
                inPictureInPicture = false,
                landscapeObservedSinceFullscreenEntry = true,
            ),
        )
    }

    @Test
    fun repeatedLandscapeSampleDoesNotReenterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                previousStableOrientation = LivePhysicalOrientation.LANDSCAPE,
                currentOrientation = LivePhysicalOrientation.LANDSCAPE,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun firstUnknownOrientationHistoryDoesNotEnterFullscreen() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                previousStableOrientation = null,
                currentOrientation = LivePhysicalOrientation.LANDSCAPE,
                livePreviewActive = true,
                inPictureInPicture = false,
            ),
        )
    }

    @Test
    fun stablePortraitExitsLiveFullscreenOnlyAfterLandscapeWasObserved() {
        assertTrue(
            LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                currentOrientation = LivePhysicalOrientation.PORTRAIT,
                liveFullscreenActive = true,
                inPictureInPicture = false,
                landscapeObservedSinceFullscreenEntry = true,
            ),
        )
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                currentOrientation = LivePhysicalOrientation.PORTRAIT,
                liveFullscreenActive = true,
                inPictureInPicture = false,
                landscapeObservedSinceFullscreenEntry = false,
            ),
        )
    }

    @Test
    fun pictureInPictureNeverTriggersFullscreenOrientationTransitions() {
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldEnterFullscreen(
                previousStableOrientation = LivePhysicalOrientation.PORTRAIT,
                currentOrientation = LivePhysicalOrientation.LANDSCAPE,
                livePreviewActive = true,
                inPictureInPicture = true,
            ),
        )
        assertFalse(
            LiveFullscreenOrientationEntryPolicy.shouldExitFullscreen(
                currentOrientation = LivePhysicalOrientation.PORTRAIT,
                liveFullscreenActive = true,
                inPictureInPicture = true,
                landscapeObservedSinceFullscreenEntry = true,
            ),
        )
    }
}
