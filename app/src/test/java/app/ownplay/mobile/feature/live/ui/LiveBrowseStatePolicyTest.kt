package app.ownplay.mobile.feature.live.ui

import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import app.ownplay.mobile.feature.live.domain.ProviderLiveCatalogSnapshot
import app.ownplay.mobile.feature.live.domain.ProviderLiveCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBrowseStatePolicyTest {
    @Test
    fun providerBrowsePreservesSnapshotOrderWithoutSynthesizingUncategorized() {
        val catalog = ProviderLiveCatalogSnapshot(
            categories = listOf(
                ProviderLiveCategory("late", "Late", 9),
                ProviderLiveCategory("first", "First", 1),
            ),
            channels = listOf(
                LiveOrganizationChannel("uncategorized", "Loose", null, null, 3),
                LiveOrganizationChannel("first-channel", "One", null, "first", 2),
            ),
        )

        assertEquals(
            listOf("late", "first"),
            LiveBrowseStatePolicy.providerCategoryOptions(catalog).map { it.categoryId },
        )
        assertEquals(
            listOf("uncategorized"),
            LiveBrowseStatePolicy.visibleProviderChannelIds(
                catalog = catalog,
                categoryId = LiveBrowseStatePolicy.PROVIDER_UNCATEGORIZED_ID,
                favoritesOnly = false,
                favoriteChannelIds = emptySet(),
            ),
        )
    }

    @Test
    fun invalidProviderSelectionFallsBackToFirstProviderCategory() {
        val catalog = ProviderLiveCatalogSnapshot(
            categories = listOf(
                ProviderLiveCategory("first", "First", 0),
                ProviderLiveCategory("second", "Second", 1),
            ),
            channels = emptyList(),
        )

        assertEquals(
            "first",
            LiveBrowseStatePolicy.selectedProviderCategoryId("missing", catalog),
        )
        assertEquals(
            "second",
            LiveBrowseStatePolicy.selectedProviderCategoryId("second", catalog),
        )
    }

    @Test
    fun searchRespectsCandidateBrowseContextAndItsOrder() {
        val channels = listOf(
            LiveOrganizationChannel("a", "News One", null, null, 0),
            LiveOrganizationChannel("b", "News Two", null, null, 1),
            LiveOrganizationChannel("c", "News Three", null, null, 2),
        )

        assertEquals(
            listOf("c", "a"),
            LiveBrowseStatePolicy.searchChannelIds(
                channels = channels,
                query = "news",
                favoritesOnly = false,
                favoriteChannelIds = emptySet(),
                candidateChannelIds = listOf("c", "a"),
            ),
        )
    }

    @Test
    fun searchMatchesProviderTvgAndLocalNamesWithoutChangingOrder() {
        val channels = listOf(
            LiveOrganizationChannel("a", "Provider News", "EPG News", null, 0, localName = "Local One"),
            LiveOrganizationChannel("b", "Provider Sport", null, null, 1),
        )

        assertEquals(
            listOf("a"),
            LiveBrowseStatePolicy.searchChannelIds(channels, "local", false, emptySet()),
        )
        assertEquals(
            listOf("a"),
            LiveBrowseStatePolicy.searchChannelIds(channels, "epg", false, emptySet()),
        )
        assertEquals(
            listOf("b"),
            LiveBrowseStatePolicy.searchChannelIds(channels, "provider", true, setOf("b")),
        )
    }

    @Test
    fun providerVisibilityFilterPreservesProviderChannelOrder() {
        val catalog = ProviderLiveCatalogSnapshot(
            categories = listOf(ProviderLiveCategory("news", "News", 0)),
            channels = listOf(
                LiveOrganizationChannel("b", "B", null, "news", 1),
                LiveOrganizationChannel("a", "A", null, "news", 0),
                LiveOrganizationChannel("c", "C", null, "news", 2),
            ),
        )

        assertEquals(
            listOf("b", "a", "c"),
            LiveBrowseStatePolicy.visibleProviderChannelIds(
                catalog = catalog,
                categoryId = "news",
                favoritesOnly = false,
                favoriteChannelIds = emptySet(),
            ),
        )
        assertEquals(
            listOf("a", "c"),
            LiveBrowseStatePolicy.visibleProviderChannelIds(
                catalog = catalog,
                categoryId = "news",
                favoritesOnly = true,
                favoriteChannelIds = setOf("c", "a"),
            ),
        )
    }
}