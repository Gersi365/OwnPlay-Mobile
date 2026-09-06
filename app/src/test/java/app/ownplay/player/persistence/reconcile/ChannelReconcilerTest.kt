package app.ownplay.player.persistence.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelReconcilerTest {
    @Test
    fun planPreservesExistingChannelIdsAndSeparatesNewAndMissing() {
        val result = ChannelReconciler.plan(
            existing = listOf(
                ExistingChannelIdentity("local-a", "provider-a"),
                ExistingChannelIdentity("local-b", "provider-b"),
            ),
            incomingProviderKeys = listOf("provider-b", "provider-c"),
        )

        assertTrue(result is ReconciliationResult.Success)
        val plan = (result as ReconciliationResult.Success).plan
        assertEquals(mapOf("provider-b" to "local-b"), plan.matchedChannelIdsByProviderKey)
        assertEquals(listOf("provider-c"), plan.newProviderKeys)
        assertEquals(listOf("local-a"), plan.missingChannelIds)
    }

    @Test
    fun incomingOrderIsPreservedForNewProviderKeys() {
        val result = ChannelReconciler.plan(
            existing = emptyList(),
            incomingProviderKeys = listOf("third", "first", "second"),
        ) as ReconciliationResult.Success

        assertEquals(listOf("third", "first", "second"), result.plan.newProviderKeys)
    }

    @Test
    fun duplicateIncomingProviderKeyIsRejectedInsteadOfSilentlyCollapsing() {
        val result = ChannelReconciler.plan(
            existing = emptyList(),
            incomingProviderKeys = listOf("duplicate", "duplicate"),
        )

        assertEquals(
            ReconciliationResult.DuplicateIncomingProviderKey("duplicate"),
            result,
        )
    }

    @Test
    fun duplicateExistingProviderKeyIsRejectedInsteadOfChoosingArbitrarily() {
        val result = ChannelReconciler.plan(
            existing = listOf(
                ExistingChannelIdentity("local-a", "duplicate"),
                ExistingChannelIdentity("local-b", "duplicate"),
            ),
            incomingProviderKeys = listOf("duplicate"),
        )

        assertEquals(
            ReconciliationResult.DuplicateExistingProviderKey("duplicate"),
            result,
        )
    }
}
