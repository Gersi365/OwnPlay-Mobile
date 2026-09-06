package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderPolicyTest {
    @Test
    fun savedOrderIsRetainedAndNewCategoriesAppendDeterministically() {
        val result = CategoryOrderPolicy.normalize(
            savedOrder = listOf("sports", "news"),
            availableCategoryKeys = listOf("news", "movies", "sports", "kids"),
        )

        assertEquals(listOf("sports", "news", "movies", "kids"), result)
    }

    @Test
    fun removedAndDuplicateCategoriesDoNotCorruptOrder() {
        val result = CategoryOrderPolicy.normalize(
            savedOrder = listOf("gone", "sports", "sports", "news"),
            availableCategoryKeys = listOf("news", "sports", "news"),
        )

        assertEquals(listOf("sports", "news"), result)
    }
}
