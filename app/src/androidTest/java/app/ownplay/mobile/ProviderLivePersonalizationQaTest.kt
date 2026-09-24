package app.ownplay.mobile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.feature.live.data.RoomLiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.ProviderLiveOrganizationContract
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderLivePersonalizationQaTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var repository: RoomLiveOrganizationRepository
    private val sourceId = SourceId("provider-personalization-qa")

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OwnPlayDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomLiveOrganizationRepository(database, database.liveOrganizationDao())
        database.sourceDao().insert(
            SourceEntity(
                sourceId = sourceId.value,
                displayName = "QA",
                type = "M3U",
                baseLocator = "https://example.invalid/list.m3u",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.refreshStateDao().upsertCategories(
            listOf(
                ProviderCategoryEntity(sourceId.value, "LIVE", "a", "a", "Alpha", 0, true, 1L),
                ProviderCategoryEntity(sourceId.value, "LIVE", "b", "b", "Beta", 1, true, 1L),
            ),
        )
        database.refreshStateDao().upsertLiveChannels(
            listOf(
                channel("a1", "a", 0),
                channel("a2", "a", 1),
                channel("b1", "b", 2),
                channel("u1", null, 3),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun providerCategoryPersonalizationIsScopedButChannelHideRemainsGlobal() = runBlocking {
        val uncategorized = ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
        assertEquals(
            listOf("a", "b", uncategorized),
            repository.observeProviderManagement(sourceId).first().categories.map { it.categoryId },
        )

        assertTrue(repository.setProviderCategoryHidden(sourceId, "a", true))
        assertFalse(repository.observeProviderCatalog(sourceId).first().categories.any { it.categoryId == "a" })
        assertTrue(repository.observeProviderManagement(sourceId).first().channels.any { it.channelId == "a1" })
        assertTrue(repository.setProviderCategoryHidden(sourceId, "a", false))

        assertTrue(repository.setFavorite(sourceId, "a1", true))
        assertEquals(setOf("a1"), repository.observeFavoriteChannelIds(sourceId).first())
        database.refreshStateDao().upsertLiveChannels(
            listOf(channel("a1", "a", 0).copy(name = "Renamed Channel a1", lastSeenGeneration = 2L)),
        )
        assertEquals(setOf("a1"), repository.observeFavoriteChannelIds(sourceId).first())
        assertTrue(repository.setFavorite(sourceId, "a1", false))
        assertTrue(repository.observeFavoriteChannelIds(sourceId).first().isEmpty())

        assertTrue(repository.setProviderCategoryOrder(sourceId, listOf("b", "a", uncategorized)))
        val visibleCatalog = repository.observeProviderCatalog(sourceId).first()
        assertEquals(
            listOf("a", "b"),
            visibleCatalog.categories.map { it.categoryId },
        )
        assertEquals(
            listOf("u1"),
            visibleCatalog.channels
                .filter { it.providerCategoryId == null }
                .map { it.channelId },
        )

        assertTrue(repository.setProviderChannelHidden(sourceId, "a", "a1", true))
        assertFalse(repository.observeProviderCatalog(sourceId).first().channels.any { it.channelId == "a1" })
        assertTrue(
            repository.observeProviderManagement(sourceId).first()
                .channels.first { it.channelId == "a1" }
                .hidden,
        )
        assertTrue(repository.setProviderChannelHidden(sourceId, "a", "a1", false))
        assertFalse(
            repository.observeProviderManagement(sourceId).first()
                .channels.first { it.channelId == "a1" }
                .hidden,
        )

        assertTrue(repository.setProviderChannelOrder(sourceId, "a", listOf("a2", "a1")))
        assertEquals(
            listOf("a1", "a2"),
            repository.observeProviderCatalog(sourceId).first().channels
                .filter { it.providerCategoryId == "a" }
                .map { it.channelId },
        )

        assertTrue(repository.resetProviderCategoryOrder(sourceId))
        assertTrue(repository.resetProviderChannelOrder(sourceId, "a"))
        assertEquals(
            listOf("a", "b"),
            repository.observeProviderCatalog(sourceId).first().categories.map { it.categoryId },
        )
        assertEquals(
            listOf("a1", "a2"),
            repository.observeProviderCatalog(sourceId).first().channels
                .filter { it.providerCategoryId == "a" }
                .map { it.channelId },
        )
    }

    private fun channel(id: String, categoryId: String?, order: Int) = LiveChannelEntity(
        channelId = id,
        sourceId = sourceId.value,
        providerKey = id,
        providerStreamId = id,
        categoryKey = categoryId,
        name = "Channel $id",
        tvgId = id,
        tvgName = "Channel $id",
        logoUrl = null,
        streamLocator = "https://example.invalid/$id.ts",
        providerOrder = order,
        available = true,
        lastSeenGeneration = 1L,
    )
}