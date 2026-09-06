package app.ownplay.player.playback

import app.ownplay.player.live.LiveCategory
import app.ownplay.player.live.LiveChannelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaybackCategoryNavigationTest {
    @Test
    fun categoryNavigationMovesToFirstChannelAndCarriesTargetContext() {
        val categoryA = category("category-a", "News", 0L)
        val categoryB = category("category-b", "Sports", 1L)
        val categoryAChannels = listOf(
            channel("a-one", "A One", categoryA),
            channel("a-two", "A Two", categoryA),
        )
        val categoryBChannels = listOf(
            channel("b-one", "B One", categoryB),
            channel("b-two", "B Two", categoryB),
        )
        val context = LivePlaybackBrowseContext.capture(
            sourceId = "source",
            visibleChannels = categoryAChannels,
            categories = listOf(categoryA, categoryB),
            categoryNavigationChannels = categoryAChannels + categoryBChannels,
            activeCategoryKey = categoryA.providerCategoryKey,
        )
        val current = context.selectionFor("a-two") ?: error("missing current selection")

        val target = current.navigateCategory(PlaybackNavigationDirection.NEXT)
            ?: error("missing target category selection")

        assertEquals("b-one", target.request.channelId)
        assertEquals("category-b", target.categoryKey)
        assertEquals("b-two", target.request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertNull(target.request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))

        val back = target.navigateCategory(PlaybackNavigationDirection.PREVIOUS)
            ?: error("missing previous category selection")
        assertEquals("a-one", back.request.channelId)
        assertEquals("category-a", back.categoryKey)
    }

    @Test
    fun categoryNavigationStopsAtVisibleCategoryBoundaries() {
        val categoryA = category("category-a", "News", 0L)
        val categoryB = category("category-b", "Sports", 1L)
        val a = channel("a-one", "A One", categoryA)
        val b = channel("b-one", "B One", categoryB)
        val context = LivePlaybackBrowseContext.capture(
            sourceId = "source",
            visibleChannels = listOf(a),
            categories = listOf(categoryA, categoryB),
            categoryNavigationChannels = listOf(a, b),
            activeCategoryKey = categoryA.providerCategoryKey,
        )
        val first = context.selectionFor(a.channelId) ?: error("missing first")
        val last = first.navigateCategory(PlaybackNavigationDirection.NEXT)
            ?: error("missing last")

        assertNull(first.navigateCategory(PlaybackNavigationDirection.PREVIOUS))
        assertNull(last.navigateCategory(PlaybackNavigationDirection.NEXT))
    }

    @Test
    fun categoryRenderingDoesNotExposeOpaqueProviderKey() {
        val secretKey = "provider-secret-category-key"
        val category = category(secretKey, "News", 0L)
        val item = channel("one", "One", category)
        val context = LivePlaybackBrowseContext.capture(
            sourceId = "source",
            visibleChannels = listOf(item),
            categories = listOf(category),
            categoryNavigationChannels = listOf(item),
            activeCategoryKey = secretKey,
        )

        assertFalse(context.toString().contains(secretKey))
        assertFalse(context.categories.single().toString().contains(secretKey))
    }

    private fun category(
        key: String,
        name: String,
        order: Long,
    ): LiveCategory = LiveCategory(
        providerCategoryKey = key,
        name = name,
        providerOrder = order,
    )

    private fun channel(
        id: String,
        name: String,
        category: LiveCategory,
    ): LiveChannelItem = LiveChannelItem(
        channelId = id,
        sourceId = "source",
        categoryKey = category.providerCategoryKey,
        categoryName = category.name,
        providerName = name,
        localDisplayName = null,
        displayName = name,
        logoRef = null,
        hasLogoOverride = false,
        providerOrder = 0L,
        manualOrder = null,
        favoriteOrder = null,
        isFavorite = false,
        isHidden = false,
        availability = "available",
        recentAtEpochMillis = null,
    )
}
