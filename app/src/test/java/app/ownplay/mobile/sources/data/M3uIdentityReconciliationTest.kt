package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uIdentityReconciliationTest {
    @Test
    fun `legacy m3u channel id survives rotating query token`() {
        val sourceId = SourceId("source-a")
        val existingCategory = ProviderCategoryEntity(
            sourceId = sourceId.value,
            kind = DefaultSourceCatalogLoader.KIND_LIVE,
            categoryKey = "legacy-news-category",
            providerKey = "News",
            name = "News",
            providerOrder = 0,
            available = true,
            lastSeenGeneration = 1L,
        )
        val existingChannel = LiveChannelEntity(
            channelId = "legacy-channel-id",
            sourceId = sourceId.value,
            providerKey = "https://stream.example/live.ts?token=old-secret",
            providerStreamId = null,
            categoryKey = existingCategory.categoryKey,
            name = "News One",
            tvgId = null,
            tvgName = "News One",
            logoUrl = null,
            streamLocator = "https://stream.example/live.ts?token=old-secret",
            providerOrder = 0,
            available = true,
            lastSeenGeneration = 1L,
        )
        val newLocator = "https://stream.example/live.ts?token=new-secret"
        val proposedId = StableIdentity.m3uLiveChannel(
            sourceId = sourceId,
            tvgId = null,
            stableLocatorHint = newLocator,
            normalizedName = "News One",
            normalizedGroup = "News",
        )
        val snapshot = ProviderCatalogSnapshot(
            sourceType = SourceType.M3U,
            categories = listOf(
                ProviderCategoryRecord(
                    kind = DefaultSourceCatalogLoader.KIND_LIVE,
                    providerKey = "News",
                    name = "News",
                    providerOrder = 0,
                ),
            ),
            liveChannels = listOf(
                ProviderLiveChannelRecord(
                    proposedChannelId = proposedId,
                    providerKey = proposedId,
                    providerStreamId = null,
                    categoryProviderKey = "News",
                    name = "News One",
                    tvgId = null,
                    tvgName = "News One",
                    logoUrl = null,
                    streamLocator = newLocator,
                    providerOrder = 0,
                ),
            ),
            movies = emptyList(),
            series = emptyList(),
        )

        val plan = CatalogReconciler.reconcile(
            sourceId = sourceId,
            sourceType = SourceType.M3U,
            generation = 2L,
            snapshot = snapshot,
            existingCategories = listOf(existingCategory),
            existingLiveChannels = listOf(existingChannel),
            existingMovies = emptyList(),
            existingSeries = emptyList(),
        )

        assertEquals("legacy-channel-id", plan.liveChannels.single().channelId)
        assertEquals(proposedId, plan.liveChannels.single().providerKey)
        assertEquals(newLocator, plan.liveChannels.single().streamLocator)
    }

    @Test
    fun `unique name and group reconcile when locator host rotates`() {
        val existing = LiveChannelEntity(
            channelId = "legacy-channel-id",
            sourceId = "source-a",
            providerKey = "legacy-provider-key",
            providerStreamId = null,
            categoryKey = "news-key",
            name = "News One",
            tvgId = null,
            tvgName = null,
            logoUrl = null,
            streamLocator = "https://edge-a.example/live.ts?token=old",
            providerOrder = 0,
            available = true,
            lastSeenGeneration = 1L,
        )
        val incoming = ProviderLiveChannelRecord(
            proposedChannelId = "new-proposed-id",
            providerKey = "new-proposed-id",
            providerStreamId = null,
            categoryProviderKey = "News",
            name = "News One",
            tvgId = null,
            tvgName = null,
            logoUrl = null,
            streamLocator = "https://edge-b.example/live.ts?token=new",
            providerOrder = 0,
        )

        val reconciled = M3uIdentityReconciliation.reconcileExistingChannelIds(
            incoming = listOf(incoming),
            existing = listOf(existing),
            existingCategories = listOf(
                ProviderCategoryEntity(
                    sourceId = "source-a",
                    kind = DefaultSourceCatalogLoader.KIND_LIVE,
                    categoryKey = "news-key",
                    providerKey = "News",
                    name = "News",
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 1L,
                ),
            ),
        )

        assertEquals("legacy-channel-id", reconciled["new-proposed-id"])
    }
}
