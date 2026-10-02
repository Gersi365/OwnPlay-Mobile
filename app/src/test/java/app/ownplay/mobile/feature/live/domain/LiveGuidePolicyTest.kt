package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGuidePolicyTest {
    @Test
    fun `schedule normalization trims filters and sorts provider programs`() {
        val schedule = LiveGuidePolicy.normalizeSchedule(
            listOf(
                LiveProgram("  Later  ", 1_300, 1_400),
                LiveProgram("   ", 900, 1_000),
                LiveProgram("Current", 1_000, 1_200),
                LiveProgram("No timestamp", null, null),
                LiveProgram("Earlier", 800, 900),
            ),
        )

        assertEquals(
            listOf("Earlier", "Current", "Later", "No timestamp"),
            schedule.map { it.title },
        )
    }

    @Test
    fun `schedule normalization uses end time as deterministic tie breaker`() {
        val schedule = LiveGuidePolicy.normalizeSchedule(
            listOf(
                LiveProgram("Longer", 1_000, 1_300),
                LiveProgram("Shorter", 1_000, 1_200),
            ),
        )

        assertEquals(listOf("Shorter", "Longer"), schedule.map { it.title })
    }

    @Test
    fun `now next are selected from provider timestamps`() {
        val guide = LiveGuidePolicy.nowNext(
            programs = listOf(
                LiveProgram("Previous", 700, 900),
                LiveProgram("Current", 900, 1_100),
                LiveProgram("Next", 1_100, 1_300),
            ),
            nowEpochSeconds = 1_000,
        )
        assertEquals("Current", guide.now?.title)
        assertEquals("Next", guide.next?.title)
        assertEquals(0.5f, LiveGuidePolicy.progressFraction(guide.now, 1_000)!!, 0.001f)
    }

    @Test
    fun `exact program boundary promotes the next program`() {
        val guide = LiveGuidePolicy.nowNext(
            listOf(LiveProgram("Current", 900, 1_100), LiveProgram("Next", 1_100, 1_300)),
            1_100,
        )
        assertEquals("Next", guide.now?.title)
        assertNull(guide.next)
    }

    @Test
    fun `future-only guide exposes next without inventing now`() {
        val guide = LiveGuidePolicy.nowNext(listOf(LiveProgram("Upcoming", 1_200, 1_400)), 1_000)
        assertNull(guide.now)
        assertEquals("Upcoming", guide.next?.title)
        assertEquals(1_200L, LiveGuidePolicy.nextBoundaryEpochSeconds(guide, 1_000))
    }

    @Test
    fun `past timed guide does not become current through list-order fallback`() {
        val guide = LiveGuidePolicy.nowNext(
            listOf(LiveProgram("Old one", 600, 800), LiveProgram("Old two", 800, 950)),
            1_000,
        )
        assertNull(guide.now)
        assertNull(guide.next)
        assertNull(LiveGuidePolicy.nextBoundaryEpochSeconds(guide, 1_000))
    }

    @Test
    fun `timestamp-less provider guide uses list order`() {
        val guide = LiveGuidePolicy.nowNext(
            listOf(LiveProgram("Provider first", null, null), LiveProgram("Provider second", null, null)),
            1_000,
        )
        assertEquals("Provider first", guide.now?.title)
        assertEquals("Provider second", guide.next?.title)
        assertNull(LiveGuidePolicy.nextBoundaryEpochSeconds(guide, 1_000))
    }
}
