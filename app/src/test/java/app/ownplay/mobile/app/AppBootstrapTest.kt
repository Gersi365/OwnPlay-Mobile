package app.ownplay.mobile.app

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceSummary
import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBootstrapTest {
    @Test
    fun `uninitialized state remains bootstrapping`() {
        assertEquals(
            AppBootstrapState.BOOTSTRAPPING,
            resolve(initialized = false),
        )
    }

    @Test
    fun `no local source needs provisioning`() {
        assertEquals(
            AppBootstrapState.NEEDS_PROVISIONING,
            resolve(),
        )
    }

    @Test
    fun `usable active local source unlocks app`() {
        assertEquals(
            AppBootstrapState.READY,
            resolve(localSourceState = LocalSourceGateState.USABLE_ACTIVE),
        )
    }

    @Test
    fun `existing source without usable active selection requires selection or repair`() {
        assertEquals(
            AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR,
            resolve(localSourceState = LocalSourceGateState.USABLE_NO_ACTIVE),
        )
        assertEquals(
            AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR,
            resolve(localSourceState = LocalSourceGateState.NEEDS_REPAIR),
        )
    }

    @Test
    fun `local source gate uses active source when it is usable`() {
        val active = source("active")
        assertEquals(
            LocalSourceGateState.USABLE_ACTIVE,
            LocalSourceGateResolver.resolve(listOf(active), active),
        )
    }

    @Test
    fun `local source gate keeps existing enabled source behind selection when none is active`() {
        assertEquals(
            LocalSourceGateState.USABLE_NO_ACTIVE,
            LocalSourceGateResolver.resolve(listOf(source("available")), null),
        )
    }

    @Test
    fun `provider authentication failure requires source repair instead of unlocking`() {
        val source = source(
            id = "auth-failed",
            refreshFailureCategory = SourceRefreshFailureCategory.AUTHENTICATION,
        )
        assertEquals(
            LocalSourceGateState.NEEDS_REPAIR,
            LocalSourceGateResolver.resolve(listOf(source), source),
        )
    }

    @Test
    fun `transient refresh failure does not block an already provisioned active source`() {
        val source = source(
            id = "offline-capable",
            refreshFailureCategory = SourceRefreshFailureCategory.NETWORK,
        )
        assertEquals(
            LocalSourceGateState.USABLE_ACTIVE,
            LocalSourceGateResolver.resolve(listOf(source), source),
        )
    }

    private fun source(
        id: String,
        enabled: Boolean = true,
        refreshFailureCategory: SourceRefreshFailureCategory? = null,
    ): SourceSummary = SourceSummary(
        sourceId = SourceId(id),
        type = SourceType.XTREAM,
        displayName = id,
        connectionLabel = "example.invalid",
        enabled = enabled,
        lastSuccessfulRefreshAtEpochMs = 1L,
        refreshFailureCategory = refreshFailureCategory,
    )

    private fun resolve(
        initialized: Boolean = true,
        localSourceState: LocalSourceGateState = LocalSourceGateState.NONE,
    ): AppBootstrapState = AppBootstrapResolver.resolve(
        AppBootstrapSnapshot(
            initialized = initialized,
            localSourceState = localSourceState,
        ),
    )
}
