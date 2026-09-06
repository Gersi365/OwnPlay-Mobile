package app.ownplay.player.source.xtream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTvProgramWindowTest {
    @Test
    fun missingOrInvalidTimesAreNotRetained() {
        assertFalse(XmlTvProgramWindow.overlaps(null, null, 100L, 200L))
        assertFalse(XmlTvProgramWindow.overlaps(150L, 150L, 100L, 200L))
        assertFalse(XmlTvProgramWindow.overlaps(160L, 150L, 100L, 200L))
    }

    @Test
    fun programmesAtWindowBoundariesAreRetained() {
        assertTrue(XmlTvProgramWindow.overlaps(50L, 100L, 100L, 200L))
        assertTrue(XmlTvProgramWindow.overlaps(200L, 250L, 100L, 200L))
    }

    @Test
    fun programmesOutsideWindowAreDropped() {
        assertFalse(XmlTvProgramWindow.overlaps(10L, 99L, 100L, 200L))
        assertFalse(XmlTvProgramWindow.overlaps(201L, 250L, 100L, 200L))
    }

    @Test
    fun oneSidedTimesRemainToleratedWhenTheyFallInsideWindow() {
        assertTrue(XmlTvProgramWindow.overlaps(150L, null, 100L, 200L))
        assertTrue(XmlTvProgramWindow.overlaps(null, 150L, 100L, 200L))
        assertFalse(XmlTvProgramWindow.overlaps(250L, null, 100L, 200L))
    }
}
