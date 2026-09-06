package app.ownplay.player.live

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.live.LiveChannelRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBrowseProjectorTest {
    private val records = listOf(
        record(
            id = "one",
            providerName = "Provider One",
            tvgName = "News One",
            categoryKey = "news",
            categoryName = "News",
            providerOrder = 2,
            manualOrder = 1,
            recentAtEpochMillis = 100,
        ),
        record(
            id = "two",
            providerName = "Sports Two",
            categoryKey = "sports",
            categoryName = "Sports",
            providerOrder = 1,
            favoriteOrder = 0,
            recentAtEpochMillis = 200,
        ),
        record(
            id = "hidden",
            providerName = "Hidden Channel",
            categoryKey = "news",
            categoryName = "News",
            providerOrder = 3,
            hiddenAtEpochMillis = 10,
        ),
        record(
            id = "removed",
            providerName = "Removed Channel",
            categoryKey = "news",
            categoryName = "News",
            providerOrder = 4,
            availability = ChannelAvailability.REMOVED,
        ),
    )

    @Test
    fun defaultProjectionExcludesHiddenAndRemovedAndUsesProviderOrder() {
        val result = LiveBrowseProjector.project(records, LiveBrowseQuery())

        assertEquals(listOf("two", "one"), result.map { it.channelId })
        assertFalse(result.any { it.isHidden })
        assertFalse(result.any { it.availability == ChannelAvailability.REMOVED })
    }

    @Test
    fun hiddenCategoryMakesEveryMemberEffectivelyHidden() {
        val normal = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(),
            hiddenCategoryKeys = setOf("news"),
        )
        assertEquals(listOf("two"), normal.map { it.channelId })

        val editing = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(includeHidden = true),
            hiddenCategoryKeys = setOf("news"),
        )
        assertEquals(listOf("two", "one", "hidden"), editing.map { it.channelId })
        assertTrue(editing.first { it.channelId == "one" }.isHidden)
        assertTrue(editing.first { it.channelId == "hidden" }.isHidden)
    }

    @Test
    fun unhidingCategoryDoesNotEraseIndependentChannelHide() {
        val result = LiveBrowseProjector.project(
            records = records,
            query = LiveBrowseQuery(includeHidden = true),
            hiddenCategoryKeys = emptySet(),
        )

        assertFalse(result.first { it.channelId == "one" }.isHidden)
        assertTrue(result.first { it.channelId == "hidden" }.isHidden)
    }

    @Test
    fun localAndTvgNamesOverrideProviderName() {
        val customized = records.first().copy(
            localDisplayName = "My News",
            logoOverrideRef = "local-logo-ref",
        )
        val result = LiveBrowseProjector.project(listOf(customized), LiveBrowseQuery())

        assertEquals("My News", result.single().displayName)
        assertEquals("Provider One", result.single().providerName)
        assertEquals("My News", result.single().localDisplayName)
        assertTrue(result.single().hasLogoOverride)
        assertEquals("local-logo-ref", result.single().logoRef)
    }

    @Test
    fun providerNameRemainsSearchableAfterLocalRename() {
        val customized = records.first().copy(localDisplayName = "My News")

        val result = LiveBrowseProjector.project(
            records = listOf(customized),
            query = LiveBrowseQuery(searchTerm = "provider one"),
        )

        assertEquals(listOf("one"), result.map { it.channelId })
        assertEquals("My News", result.single().displayName)
    }

    @Test
    fun transientFilteringMatchesDirectProjectionSemantics() {
        val customized = records.map { record ->
            if (record.channelId == "one") record.copy(localDisplayName = "My News") else record
        }
        val queries = listOf(
            LiveBrowseQuery(searchTerm = "provider one"),
            LiveBrowseQuery(searchTerm = "NEWS", categoryKey = "news"),
            LiveBrowseQuery(
                searchTerm = "news",
                categoryKey = "news",
                includeHidden = true,
                order = LiveBrowseOrder.MY_ORDER,
            ),
            LiveBrowseQuery(
                searchTerm = "sports",
                favoritesOnly = true,
                order = LiveBrowseOrder.A_TO_Z,
            ),
        )

        queries.forEach { query ->
            val navigation = LiveBrowseProjector.project(
                records = customized,
                query = query.copy(searchTerm = "", categoryKey = null),
            )
            val transient = LiveBrowseProjector.filterTransient(
                navigationChannels = navigation,
                query = query,
            )
            val direct = LiveBrowseProjector.project(
                records = customized,
                query = query,
            )

            assertEquals(direct, transient)
        }
    }

    @Test
    fun searchAndCategoryFilteringDoNotMutatePersistentOrdering() {
        val query = LiveBrowseQuery(
            searchTerm = "news",
            categoryKey = "news",
            order = LiveBrowseOrder.MY_ORDER,
        )

        val result = LiveBrowseProjector.project(records, query)

        assertEquals(listOf("one"), result.map { it.channelId })
        assertEquals(1L, result.single().manualOrder)
    }

    @Test
    fun favoritesCanUseTheirOwnPersistentOrder() {
        val extraFavorite = records.first().copy(favoriteOrder = 5)
        val query = LiveBrowseQuery(
            favoritesOnly = true,
            order = LiveBrowseOrder.FAVORITE_ORDER,
        )

        val result = LiveBrowseProjector.project(
            records = listOf(extraFavorite, records[1]),
            query = query,
        )

        assertEquals(listOf("two", "one"), result.map { it.channelId })
        assertTrue(result.all { it.isFavorite })
    }

    @Test
    fun recentlyWatchedOrdersMostRecentFirstAndKeepsNeverWatchedLast() {
        val neverWatched = records.first().copy(
            channelId = "never",
            providerOrder = 0,
            recentAtEpochMillis = null,
        )
        val result = LiveBrowseProjector.project(
            records = listOf(neverWatched, records[0], records[1]),
            query = LiveBrowseQuery(order = LiveBrowseOrder.RECENTLY_WATCHED),
        )

        assertEquals(listOf("two", "one", "never"), result.map { it.channelId })
    }

    @Test
    fun myOrderKeepsUnorderedNewChannelsAfterEstablishedManualOrder() {
        val result = LiveBrowseProjector.project(
            records = records.take(2),
            query = LiveBrowseQuery(order = LiveBrowseOrder.MY_ORDER),
        )

        assertEquals(listOf("one", "two"), result.map { it.channelId })
    }

    private fun record(
        id: String,
        providerName: String,
        tvgName: String? = null,
        categoryKey: String? = null,
        categoryName: String? = null,
        providerOrder: Long,
        manualOrder: Long? = null,
        favoriteOrder: Long? = null,
        hiddenAtEpochMillis: Long? = null,
        availability: String = ChannelAvailability.AVAILABLE,
        recentAtEpochMillis: Long? = null,
    ) = LiveChannelRecord(
        channelId = id,
        sourceId = "source",
        providerCategoryKey = categoryKey,
        categoryName = categoryName,
        providerName = providerName,
        tvgName = tvgName,
        logoRef = null,
        providerOrder = providerOrder,
        availability = availability,
        localDisplayName = null,
        logoOverrideRef = null,
        manualOrder = manualOrder,
        favoriteOrder = favoriteOrder,
        hiddenAtEpochMillis = hiddenAtEpochMillis,
        recentAtEpochMillis = recentAtEpochMillis,
    )
}
