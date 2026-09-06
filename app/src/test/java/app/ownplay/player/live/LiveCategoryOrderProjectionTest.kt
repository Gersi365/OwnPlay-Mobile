package app.ownplay.player.live

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCategoryOrderProjectionTest {
    @Test
    fun manualCategoriesLeadAndNewProviderCategoriesAppend() = runBlocking {
        val snapshot = LiveCatalogSnapshot(
            categories = listOf(
                LiveCategory("new", "New", providerOrder = 0),
                LiveCategory("news", "News", providerOrder = 1, manualOrder = 1),
                LiveCategory("sports", "Sports", providerOrder = 2, manualOrder = 0),
            ),
            channels = emptyList(),
        )

        val state = LiveBrowseSession().observe(flowOf(snapshot)).first()

        assertEquals(
            listOf("sports", "news", "new"),
            state.categories.map(LiveCategory::providerCategoryKey),
        )
    }
}
