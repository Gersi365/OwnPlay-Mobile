package app.ownplay.player.live.ingest

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.ProviderCategoryEntity
import app.ownplay.player.persistence.ProviderChannelEntity
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.source.m3u.M3uEntry
import app.ownplay.player.source.m3u.M3uPlaylist
import app.ownplay.player.source.xtream.XtreamCategory
import app.ownplay.player.source.xtream.XtreamLiveStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialLiveCatalogIngestorTest {
    @Test
    fun m3uFactoryCreatesStableGroupCategoryAndRedactsLocatorFromRendering() {
        val catalog = InitialLiveCatalogFactory.fromM3u(
            M3uPlaylist(
                entries = listOf(
                    M3uEntry(
                        displayName = "News One",
                        streamUrl = "https://example.test/live.m3u8?token=secret",
                        tvgId = "news.one",
                        logoUrl = "https://example.test/logo.png?token=logo-secret",
                        groupTitle = " News ",
                    ),
                    M3uEntry(
                        displayName = "News Two",
                        streamUrl = "https://example.test/two.m3u8",
                        groupTitle = "news",
                    ),
                ),
            ),
        )

        assertEquals(1, catalog.categories.size)
        assertTrue(catalog.categories.single().providerKey.startsWith("m3u:group:"))
        assertEquals(catalog.categories.single().providerKey, catalog.channels[0].providerCategoryKey)
        assertEquals(catalog.categories.single().providerKey, catalog.channels[1].providerCategoryKey)
        assertFalse(catalog.channels.first().toString().contains("secret"))
        assertFalse(catalog.channels.first().toString().contains("logo-secret"))
    }

    @Test
    fun xtreamFactoryUsesVersionedDescriptorWhenDirectSourceIsUnavailable() {
        val catalog = InitialLiveCatalogFactory.fromXtream(
            categories = listOf(XtreamCategory("1", "News", null)),
            streams = listOf(
                XtreamLiveStream(
                    streamId = 42,
                    name = "News",
                    categoryId = "1",
                    iconUrl = null,
                    epgChannelId = "news.epg",
                    archiveDurationDays = null,
                    directSource = null,
                ),
            ),
        )

        assertEquals("xtream:live:42", catalog.channels.single().providerKey)
        assertEquals("ownplay-locator-v1|xtream-live|42", catalog.channels.single().locatorValue)
    }

    @Test
    fun matchedChannelKeepsLocalIdAndReplacesOldOpaqueRefs() = runBlocking {
        val store = FakeSensitiveValueStore().apply {
            seed("old-stream", "old locator")
            seed("old-logo", "old logo")
        }
        val persistence = FakePersistence(
            existing = listOf(
                channel(
                    channelId = "local-channel",
                    providerKey = "xtream:live:42",
                    streamRef = "old-stream",
                    logoRef = "old-logo",
                ),
            ),
        )
        val result = InitialLiveCatalogIngestor(persistence, store).ingest(
            sourceId = "source",
            generation = 3,
            catalog = IncomingLiveCatalog(
                categories = listOf(IncomingLiveCategory("1", "News", null, 0)),
                channels = listOf(
                    IncomingLiveChannel(
                        providerKey = "xtream:live:42",
                        providerStreamId = "42",
                        providerCategoryKey = "1",
                        providerName = "News HD",
                        tvgId = "news.epg",
                        tvgName = null,
                        locatorValue = "ownplay-locator-v1|xtream-live|42",
                        logoValue = "https://example.test/logo.png",
                        providerOrder = 0,
                    ),
                ),
            ),
        )

        assertEquals(InitialLiveCatalogIngestResult.Success(1, 1), result)
        val saved = persistence.savedChannels.single()
        assertEquals("local-channel", saved.channelId)
        assertEquals(3L, saved.lastSeenGeneration)
        assertEquals(ChannelAvailability.AVAILABLE, saved.availability)
        assertFalse(store.contains("old-stream"))
        assertFalse(store.contains("old-logo"))
        assertTrue(store.contains(saved.streamLocatorRef))
        assertTrue(store.contains(saved.logoRef!!))
    }

    @Test
    fun duplicateChannelKeysFailBeforeSecureValuesAreAllocated() = runBlocking {
        val store = FakeSensitiveValueStore()
        val persistence = FakePersistence()
        val duplicate = IncomingLiveChannel(
            providerKey = "same",
            providerStreamId = null,
            providerCategoryKey = null,
            providerName = "One",
            tvgId = null,
            tvgName = null,
            locatorValue = "ownplay-locator-v1|direct|https://example.test/one",
            logoValue = null,
            providerOrder = 0,
        )

        val result = InitialLiveCatalogIngestor(persistence, store).ingest(
            sourceId = "source",
            generation = 1,
            catalog = IncomingLiveCatalog(
                categories = emptyList(),
                channels = listOf(duplicate, duplicate.copy(providerName = "Two")),
            ),
        )

        assertEquals(InitialLiveCatalogIngestResult.DuplicateChannelKey, result)
        assertEquals(0, store.size)
        assertTrue(persistence.savedChannels.isEmpty())
    }

    @Test
    fun persistenceFailureRemovesNewlyAllocatedSensitiveValues() = runBlocking {
        val store = FakeSensitiveValueStore()
        val persistence = FakePersistence(failApply = true)
        val result = InitialLiveCatalogIngestor(persistence, store).ingest(
            sourceId = "source",
            generation = 1,
            catalog = IncomingLiveCatalog(
                categories = emptyList(),
                channels = listOf(
                    IncomingLiveChannel(
                        providerKey = "provider",
                        providerStreamId = null,
                        providerCategoryKey = null,
                        providerName = "Channel",
                        tvgId = null,
                        tvgName = null,
                        locatorValue = "ownplay-locator-v1|direct|https://example.test/stream",
                        logoValue = "https://example.test/logo",
                        providerOrder = 0,
                    ),
                ),
            ),
        )

        assertEquals(InitialLiveCatalogIngestResult.PersistenceFailure, result)
        assertEquals(0, store.size)
    }

    private fun channel(
        channelId: String,
        providerKey: String,
        streamRef: String,
        logoRef: String?,
    ) = ProviderChannelEntity(
        channelId = channelId,
        sourceId = "source",
        providerKey = providerKey,
        providerStreamId = null,
        providerCategoryKey = null,
        providerName = "Old Name",
        tvgId = null,
        tvgName = null,
        logoRef = logoRef,
        streamLocatorRef = streamRef,
        providerOrder = 0,
        availability = ChannelAvailability.AVAILABLE,
        lastSeenGeneration = 1,
    )

    private class FakePersistence(
        private val existing: List<ProviderChannelEntity> = emptyList(),
        private val failApply: Boolean = false,
    ) : LiveCatalogPersistence {
        var savedCategories: List<ProviderCategoryEntity> = emptyList()
        var savedChannels: List<ProviderChannelEntity> = emptyList()

        override suspend fun existingChannels(sourceId: String): List<ProviderChannelEntity> = existing

        override suspend fun applyInitialCatalog(
            categories: List<ProviderCategoryEntity>,
            channels: List<ProviderChannelEntity>,
        ) {
            if (failApply) error("fixture persistence failure")
            savedCategories = categories
            savedChannels = channels
        }
    }

    private class FakeSensitiveValueStore : SensitiveValueStore {
        private val values = linkedMapOf<String, String>()
        private var nextId = 1

        val size: Int get() = values.size

        override fun put(value: String): SensitiveValueRef {
            val ref = SensitiveValueRef("ref-${nextId++}")
            values[ref.value] = value
            return ref
        }

        override fun get(ref: SensitiveValueRef): String? = values[ref.value]

        override fun delete(ref: SensitiveValueRef) {
            values.remove(ref.value)
        }

        fun seed(ref: String, value: String) {
            values[ref] = value
        }

        fun contains(ref: String): Boolean = ref in values
    }
}
