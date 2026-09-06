package app.ownplay.player.live.ingest

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.ProviderCategoryEntity
import app.ownplay.player.persistence.ProviderChannelEntity
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRefreshPublicationTest {
    @Test
    fun clockRollbackAdvancesPastPersistedGeneration() = runBlocking {
        val existing = channel(
            generation = 2_000L,
            streamRef = "old-stream",
        )
        val persistence = FakePersistence(existing)
        val store = FakeSensitiveValueStore().apply {
            seed("old-stream", "old locator")
        }

        val result = InitialLiveCatalogIngestor(persistence, store).ingest(
            sourceId = "source",
            generation = 1_500L,
            catalog = IncomingLiveCatalog(
                categories = emptyList(),
                channels = listOf(
                    IncomingLiveChannel(
                        providerKey = "provider",
                        providerStreamId = "42",
                        providerCategoryKey = null,
                        providerName = "Channel",
                        tvgId = null,
                        tvgName = null,
                        locatorValue = "new locator",
                        logoValue = null,
                        providerOrder = 0,
                    ),
                ),
            ),
        )

        assertEquals(InitialLiveCatalogIngestResult.Success(0, 1), result)
        assertEquals(2_001L, persistence.savedChannels.single().lastSeenGeneration)
    }

    private fun channel(
        generation: Long,
        streamRef: String,
    ) = ProviderChannelEntity(
        channelId = "local-channel",
        sourceId = "source",
        providerKey = "provider",
        providerStreamId = "42",
        providerCategoryKey = null,
        providerName = "Old Channel",
        tvgId = null,
        tvgName = null,
        logoRef = null,
        streamLocatorRef = streamRef,
        providerOrder = 0,
        availability = ChannelAvailability.AVAILABLE,
        lastSeenGeneration = generation,
    )

    private class FakePersistence(
        private val existing: ProviderChannelEntity,
    ) : LiveCatalogPersistence {
        var savedChannels: List<ProviderChannelEntity> = emptyList()

        override suspend fun existingChannels(sourceId: String): List<ProviderChannelEntity> =
            listOf(existing)

        override suspend fun applyInitialCatalog(
            categories: List<ProviderCategoryEntity>,
            channels: List<ProviderChannelEntity>,
        ) {
            savedChannels = channels
        }
    }

    private class FakeSensitiveValueStore : SensitiveValueStore {
        private val values = linkedMapOf<String, String>()
        private var nextId = 1

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
    }
}
