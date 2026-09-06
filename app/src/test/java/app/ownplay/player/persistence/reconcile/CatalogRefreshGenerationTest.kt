package app.ownplay.player.persistence.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshGenerationTest {
    @Test
    fun repeatedTimestampStillProducesDistinctIncreasingGenerations() {
        val clock = CatalogGenerationClock()

        val first = clock.next(1_000L)
        val second = clock.next(1_000L)

        assertEquals(1_000L, first)
        assertEquals(1_001L, second)
    }

    @Test
    fun clockRollbackStillProducesIncreasingGeneration() {
        val clock = CatalogGenerationClock()

        val first = clock.next(2_000L)
        val second = clock.next(1_500L)

        assertEquals(2_000L, first)
        assertEquals(2_001L, second)
    }

    @Test
    fun laterWallClockValueIsUsedWhenItAdvancesPastLastGeneration() {
        val clock = CatalogGenerationClock()

        val first = clock.next(2_000L)
        val second = clock.next(3_000L)

        assertTrue(second > first)
        assertEquals(3_000L, second)
    }
}
