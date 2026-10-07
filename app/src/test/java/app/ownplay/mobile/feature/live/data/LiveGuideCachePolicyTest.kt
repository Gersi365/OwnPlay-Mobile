package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuideCachePolicyTest {
    @Test
    fun olderUsableGuideBeatsNewerEmptySnapshot() {
        val selected = LiveGuideCachePolicy.selectForNowNext(
            candidates = listOf(
                LiveGuideCacheCandidate(
                    fetchedAtMs = 100L,
                    programs = listOf(LiveProgram("Current", 900, 1_100)),
                ),
                LiveGuideCacheCandidate(fetchedAtMs = 200L, programs = emptyList()),
            ),
            nowEpochSeconds = 1_000,
        )
        assertEquals("Current", selected?.programs?.single()?.title)
    }

    @Test
    fun validCurrentGuideBeatsNewerUntimedRows() {
        val selected = LiveGuideCachePolicy.selectForNowNext(
            candidates = listOf(
                LiveGuideCacheCandidate(
                    fetchedAtMs = 100L,
                    programs = listOf(LiveProgram("Current", 900, 1_100)),
                ),
                LiveGuideCacheCandidate(
                    fetchedAtMs = 200L,
                    programs = listOf(LiveProgram("Untimed", null, null)),
                ),
            ),
            nowEpochSeconds = 1_000,
        )
        assertEquals("Current", selected?.programs?.single()?.title)
    }

    @Test
    fun successfulEmptyRefreshDoesNotEraseLastGoodPrograms() {
        val lastGood = listOf(LiveProgram("Last good", 900, 1_100))
        assertTrue(LiveGuideCachePolicy.shouldPreserveLastGood(lastGood, emptyList()))
        assertFalse(LiveGuideCachePolicy.shouldPreserveLastGood(emptyList(), emptyList()))
        assertFalse(
            LiveGuideCachePolicy.shouldPreserveLastGood(
                lastGood,
                listOf(LiveProgram("Replacement", 1_100, 1_300)),
            ),
        )
    }
}
