package app.ownplay.player.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryContinueWatchingTest {
    @Test
    fun `progress is unavailable without a positive duration`() {
        assertNull(progressFraction(positionMs = 10_000L, durationMs = null))
        assertNull(progressFraction(positionMs = 10_000L, durationMs = 0L))
        assertNull(progressFraction(positionMs = 10_000L, durationMs = -1L))
    }

    @Test
    fun `progress uses the watched fraction`() {
        assertEquals(0.25f, progressFraction(positionMs = 15_000L, durationMs = 60_000L)!!, 0.0001f)
    }

    @Test
    fun `progress clamps to visible bounds`() {
        assertEquals(0f, progressFraction(positionMs = -5_000L, durationMs = 60_000L)!!, 0.0001f)
        assertEquals(1f, progressFraction(positionMs = 75_000L, durationMs = 60_000L)!!, 0.0001f)
    }

    @Test
    fun `resume label includes rounded watched percent`() {
        assertEquals(
            "Resume · 25%",
            continueWatchingResumeLabel(positionMs = 15_000L, durationMs = 60_000L),
        )
        assertEquals(
            "Resume · 33%",
            continueWatchingResumeLabel(positionMs = 20_000L, durationMs = 60_000L),
        )
    }

    @Test
    fun `resume label falls back when duration is unavailable`() {
        assertEquals("Resume", continueWatchingResumeLabel(positionMs = 15_000L, durationMs = null))
        assertEquals("Resume", continueWatchingResumeLabel(positionMs = 15_000L, durationMs = 0L))
    }
}
