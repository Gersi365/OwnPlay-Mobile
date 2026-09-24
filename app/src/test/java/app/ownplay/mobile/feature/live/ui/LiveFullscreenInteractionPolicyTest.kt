package app.ownplay.mobile.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFullscreenInteractionPolicyTest {
    @Test
    fun dominantAxisToleratesSmallDiagonalButRejectsAmbiguousDrag() {
        assertEquals(
            LiveFullscreenGestureAxis.HORIZONTAL,
            LiveFullscreenInteractionPolicy.classifyDrag(
                totalDx = -120f,
                totalDy = 30f,
                minimumDistancePx = 40f,
            ),
        )
        assertEquals(
            LiveFullscreenGestureAxis.VERTICAL,
            LiveFullscreenInteractionPolicy.classifyDrag(
                totalDx = 20f,
                totalDy = -100f,
                minimumDistancePx = 40f,
            ),
        )
        assertEquals(
            LiveFullscreenGestureAxis.AMBIGUOUS,
            LiveFullscreenInteractionPolicy.classifyDrag(
                totalDx = 70f,
                totalDy = 65f,
                minimumDistancePx = 40f,
            ),
        )
    }

    @Test
    fun horizontalDirectionMapsLeftToNextAndRightToPrevious() {
        assertEquals(LiveChannelStep.NEXT, LiveFullscreenInteractionPolicy.channelStep(-10f))
        assertEquals(LiveChannelStep.PREVIOUS, LiveFullscreenInteractionPolicy.channelStep(10f))
        assertNull(LiveFullscreenInteractionPolicy.channelStep(0f))
    }

    @Test
    fun channelTraversalNeverWraps() {
        val ids = listOf("a", "b", "c")
        assertEquals(
            "b",
            LiveFullscreenInteractionPolicy.adjacentChannelId(ids, "a", 0, LiveChannelStep.NEXT),
        )
        assertNull(
            LiveFullscreenInteractionPolicy.adjacentChannelId(ids, "a", 0, LiveChannelStep.PREVIOUS),
        )
        assertNull(
            LiveFullscreenInteractionPolicy.adjacentChannelId(ids, "c", 2, LiveChannelStep.NEXT),
        )
    }

    @Test
    fun removedFavoriteUsesFormerAnchorWithoutCountingRemovedChannel() {
        val remaining = listOf("a", "c", "d")
        assertEquals(
            "c",
            LiveFullscreenInteractionPolicy.adjacentChannelId(
                orderedChannelIds = remaining,
                currentChannelId = "b",
                lastKnownIndex = 1,
                step = LiveChannelStep.NEXT,
            ),
        )
        assertEquals(
            "a",
            LiveFullscreenInteractionPolicy.adjacentChannelId(
                orderedChannelIds = remaining,
                currentChannelId = "b",
                lastKnownIndex = 1,
                step = LiveChannelStep.PREVIOUS,
            ),
        )
    }
}
