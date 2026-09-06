package app.ownplay.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFullscreenEpgPolicyTest {
    @Test
    fun fullGuideFollowsVisibleProgrammeCount() {
        assertEquals(0, LiveFullscreenEpgPolicy.fullGuideIndex(0))
        assertEquals(3, LiveFullscreenEpgPolicy.fullGuideIndex(3))
    }

    @Test
    fun timelineRequiresAtLeastOneProgramme() {
        assertFalse(LiveFullscreenEpgPolicy.canEnterTimeline(0))
        assertTrue(LiveFullscreenEpgPolicy.canEnterTimeline(1))
    }
}
