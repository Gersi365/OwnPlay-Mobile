package app.ownplay.player.personalization

import app.ownplay.player.persistence.ChannelCustomizationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualOrderCustomizationMergerTest {
    @Test
    fun mergePreservesExistingRenameAndLogoOverrideWhileChangingOrder() {
        val existing = listOf(
            ChannelCustomizationEntity(
                channelId = "one",
                localDisplayName = "My News",
                logoOverrideRef = "logo-ref",
                manualOrder = 99,
            ),
        )
        val plan = ManualOrderPlan(
            assignments = listOf(
                ManualOrderAssignment("one", 0),
                ManualOrderAssignment("two", 1),
            ),
        )

        val merged = ManualOrderCustomizationMerger.merge(plan, existing)

        assertEquals(2, merged.size)
        assertEquals(
            ChannelCustomizationEntity(
                channelId = "one",
                localDisplayName = "My News",
                logoOverrideRef = "logo-ref",
                manualOrder = 0,
            ),
            merged[0],
        )
        assertEquals("two", merged[1].channelId)
        assertEquals(1L, merged[1].manualOrder)
        assertNull(merged[1].localDisplayName)
        assertNull(merged[1].logoOverrideRef)
    }

    @Test
    fun mergeUsesPlanOrderRatherThanExistingRowOrder() {
        val existing = listOf(
            ChannelCustomizationEntity(channelId = "three", manualOrder = 0),
            ChannelCustomizationEntity(channelId = "one", manualOrder = 1),
        )
        val plan = ManualOrderPlan(
            assignments = listOf(
                ManualOrderAssignment("one", 0),
                ManualOrderAssignment("two", 1),
                ManualOrderAssignment("three", 2),
            ),
        )

        val merged = ManualOrderCustomizationMerger.merge(plan, existing)

        assertEquals(listOf("one", "two", "three"), merged.map { it.channelId })
        assertEquals(listOf(0L, 1L, 2L), merged.map { it.manualOrder })
    }
}
