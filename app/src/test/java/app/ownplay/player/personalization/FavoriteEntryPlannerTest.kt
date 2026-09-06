package app.ownplay.player.personalization

import app.ownplay.player.persistence.FavoriteEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteEntryPlannerTest {
    @Test
    fun addAppendsNewFavoritesInSourceOrderAndPreservesExistingAddedAt() {
        val existing = listOf(
            FavoriteEntryEntity("existing", favoriteOrder = 0, addedAtEpochMillis = 10),
        )

        val plan = FavoriteEntryPlanner.add(
            existing = existing,
            selectedChannelIdsInSourceOrder = listOf("new-two", "existing", "new-four"),
            addedAtEpochMillis = 50,
        )

        assertEquals(listOf("existing", "new-two", "new-four"), plan.channelIds)
        assertEquals(listOf(0L, 1L, 2L), plan.entries.map { it.favoriteOrder })
        assertEquals(listOf(10L, 50L, 50L), plan.entries.map { it.addedAtEpochMillis })
    }

    @Test
    fun addingExistingFavoriteIsIdempotentAndDoesNotResetAddedAt() {
        val existing = listOf(
            FavoriteEntryEntity("one", favoriteOrder = 0, addedAtEpochMillis = 12),
            FavoriteEntryEntity("two", favoriteOrder = 1, addedAtEpochMillis = 13),
        )

        val plan = FavoriteEntryPlanner.add(
            existing = existing,
            selectedChannelIdsInSourceOrder = listOf("two"),
            addedAtEpochMillis = 999,
        )

        assertEquals(existing, plan.entries)
    }

    @Test
    fun removeNormalizesOrderAndPreservesAddedAt() {
        val existing = listOf(
            FavoriteEntryEntity("one", favoriteOrder = 0, addedAtEpochMillis = 10),
            FavoriteEntryEntity("two", favoriteOrder = 4, addedAtEpochMillis = 20),
            FavoriteEntryEntity("three", favoriteOrder = 8, addedAtEpochMillis = 30),
        )

        val plan = FavoriteEntryPlanner.remove(existing, setOf("two"))

        assertEquals(listOf("one", "three"), plan.channelIds)
        assertEquals(listOf(0L, 1L), plan.entries.map { it.favoriteOrder })
        assertEquals(listOf(10L, 30L), plan.entries.map { it.addedAtEpochMillis })
    }

    @Test
    fun reorderUsesManualPlanAndPreservesFavoriteTimestamps() {
        val existing = listOf(
            FavoriteEntryEntity("one", favoriteOrder = 0, addedAtEpochMillis = 10),
            FavoriteEntryEntity("two", favoriteOrder = 1, addedAtEpochMillis = 20),
            FavoriteEntryEntity("three", favoriteOrder = 2, addedAtEpochMillis = 30),
        )
        val manualPlan = ManualOrderPlan(
            assignments = listOf(
                ManualOrderAssignment("three", 0),
                ManualOrderAssignment("one", 1),
                ManualOrderAssignment("two", 2),
            ),
        )

        val plan = FavoriteEntryPlanner.reorder(existing, manualPlan)

        assertEquals(listOf("three", "one", "two"), plan.channelIds)
        assertEquals(listOf(30L, 10L, 20L), plan.entries.map { it.addedAtEpochMillis })
        assertEquals(listOf(0L, 1L, 2L), plan.entries.map { it.favoriteOrder })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reorderRejectsPlanThatDoesNotMatchExistingFavoriteSet() {
        FavoriteEntryPlanner.reorder(
            existing = listOf(
                FavoriteEntryEntity("one", favoriteOrder = 0, addedAtEpochMillis = 10),
            ),
            manualOrderPlan = ManualOrderPlan(
                assignments = listOf(
                    ManualOrderAssignment("other", 0),
                ),
            ),
        )
    }
}
