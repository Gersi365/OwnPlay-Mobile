package app.ownplay.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFullscreenEpgPolicyTest {
    @Test
    fun `timeline is entered only when epg programmes exist`() {
        assertFalse(LiveFullscreenEpgPolicy.canEnterTimeline(programCount = 0))
        assertTrue(LiveFullscreenEpgPolicy.canEnterTimeline(programCount = 1))
    }

    @Test
    fun `right navigation reaches full guide after last visible programme`() {
        assertEquals(
            3,
            LiveFullscreenEpgPolicy.fullGuideIndex(programCount = 3),
        )
        assertEquals(
            3,
            LiveFullscreenEpgPolicy.moveSelection(
                currentIndex = 2,
                direction = LiveFullscreenEpgDirection.RIGHT,
                programCount = 3,
            ),
        )
        assertTrue(
            LiveFullscreenEpgPolicy.isFullGuideSelection(
                selectedIndex = 3,
                programCount = 3,
            ),
        )
    }

    @Test
    fun `left and right navigation clamp to valid range`() {
        assertEquals(
            0,
            LiveFullscreenEpgPolicy.moveSelection(
                currentIndex = 0,
                direction = LiveFullscreenEpgDirection.LEFT,
                programCount = 4,
            ),
        )
        assertEquals(
            4,
            LiveFullscreenEpgPolicy.moveSelection(
                currentIndex = 4,
                direction = LiveFullscreenEpgDirection.RIGHT,
                programCount = 4,
            ),
        )
    }

    @Test
    fun `empty timeline never exposes full guide selection`() {
        assertEquals(0, LiveFullscreenEpgPolicy.fullGuideIndex(programCount = 0))
        assertFalse(
            LiveFullscreenEpgPolicy.isFullGuideSelection(
                selectedIndex = 0,
                programCount = 0,
            ),
        )
    }
}
