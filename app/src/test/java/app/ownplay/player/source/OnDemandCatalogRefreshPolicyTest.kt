package app.ownplay.player.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandCatalogRefreshPolicyTest {
    @Test
    fun automaticRefreshUsesRecentSuccessfulCatalogWithoutProviderCall() {
        val now = 50_000_000L

        assertFalse(
            shouldRefreshOnDemandCatalog(
                mode = OnDemandCatalogRefreshMode.AUTOMATIC,
                lastSuccessAtEpochMillis = now - 60_000L,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun automaticRefreshCallsProviderWhenFreshnessIsUnknown() {
        assertTrue(
            shouldRefreshOnDemandCatalog(
                mode = OnDemandCatalogRefreshMode.AUTOMATIC,
                lastSuccessAtEpochMillis = null,
                nowEpochMillis = 50_000_000L,
            ),
        )
    }

    @Test
    fun automaticRefreshCallsProviderWhenCatalogIsStale() {
        val now = 50_000_000L

        assertTrue(
            shouldRefreshOnDemandCatalog(
                mode = OnDemandCatalogRefreshMode.AUTOMATIC,
                lastSuccessAtEpochMillis = now - SOURCE_REFRESH_STALE_MILLIS,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun automaticRefreshTreatsWallClockRollbackAsStale() {
        assertTrue(
            shouldRefreshOnDemandCatalog(
                mode = OnDemandCatalogRefreshMode.AUTOMATIC,
                lastSuccessAtEpochMillis = 50_000_000L,
                nowEpochMillis = 49_000_000L,
            ),
        )
    }

    @Test
    fun manualRefreshAlwaysCallsProviderEvenWhenCatalogIsFresh() {
        val now = 50_000_000L

        assertTrue(
            shouldRefreshOnDemandCatalog(
                mode = OnDemandCatalogRefreshMode.MANUAL,
                lastSuccessAtEpochMillis = now,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun invocationGateKeepsCurrentSourceManualButSourceTransitionsAutomatic() {
        val gate = OnDemandCatalogRefreshInvocationGate()

        assertEquals(OnDemandCatalogRefreshMode.AUTOMATIC, gate.nextMode("source-a"))
        assertEquals(OnDemandCatalogRefreshMode.MANUAL, gate.nextMode("source-a"))
        assertEquals(OnDemandCatalogRefreshMode.AUTOMATIC, gate.nextMode("source-b"))
        assertEquals(OnDemandCatalogRefreshMode.MANUAL, gate.nextMode("source-b"))
        assertEquals(OnDemandCatalogRefreshMode.AUTOMATIC, gate.nextMode("source-a"))
        assertEquals(OnDemandCatalogRefreshMode.MANUAL, gate.nextMode("source-a"))
    }
}
