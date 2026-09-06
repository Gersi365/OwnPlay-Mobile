package app.ownplay.player.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogRefreshDemandTest {
    @Test
    fun sameTargetIsClaimedOnlyOnce() {
        val demand = LibraryCatalogRefreshDemand()

        assertTrue(demand.claim(LibraryCatalogRefreshTarget.MOVIES))
        assertFalse(demand.claim(LibraryCatalogRefreshTarget.MOVIES))
    }

    @Test
    fun movieAndSeriesTargetsAreIndependent() {
        val demand = LibraryCatalogRefreshDemand()

        assertTrue(demand.claim(LibraryCatalogRefreshTarget.MOVIES))
        assertTrue(demand.claim(LibraryCatalogRefreshTarget.SERIES))
        assertFalse(demand.claim(LibraryCatalogRefreshTarget.MOVIES))
        assertFalse(demand.claim(LibraryCatalogRefreshTarget.SERIES))
    }

    @Test
    fun newDemandInstanceStartsFreshForAnotherSourceLifecycle() {
        val first = LibraryCatalogRefreshDemand()
        val second = LibraryCatalogRefreshDemand()

        assertTrue(first.claim(LibraryCatalogRefreshTarget.SERIES))
        assertFalse(first.claim(LibraryCatalogRefreshTarget.SERIES))
        assertTrue(second.claim(LibraryCatalogRefreshTarget.SERIES))
    }
}
