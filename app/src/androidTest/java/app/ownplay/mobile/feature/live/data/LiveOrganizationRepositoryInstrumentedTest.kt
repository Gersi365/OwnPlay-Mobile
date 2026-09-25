package app.ownplay.mobile.feature.live.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.SourceEntity
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
class LiveOrganizationRepositoryInstrumentedTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var repository: RoomLiveOrganizationRepository
    private lateinit var refreshStore: RoomLiveOrganizationRefreshStore
    private val sourceId = SourceId("source-live")

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OwnPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomLiveOrganizationRepository(database, database.liveOrganizationDao())
        refreshStore = RoomLiveOrganizationRefreshStore(database.liveOrganizationDao())

        database.sourceDao().insert(
            SourceEntity(
                sourceId = sourceId.value,
                displayName = "Live fixture",
                type = "XTREAM",
                baseLocator = "https://fixture.invalid",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.refreshStateDao().upsertCategories(
            listOf(
                category("al-news", "AL News", 0),
                category("it-sports", "IT Sports", 1),
            ),
        )
        database.refreshStateDao().upsertLiveChannels(
            listOf(
                channel("a", "al-news", "AL News A", 0),
                channel("b", "al-news", "AL News B", 1),
                channel("c", "it-sports", "IT Sport C", 0),
            ),
        )
        reconcile(generation = 1)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun providerOnlyRefreshDoesNotGenerateOwnPlayTaxonomy() = runBlocking {
        assertTrue(
            database.liveOrganizationDao()
                .getOwnPlayCategoriesForCompatibility(sourceId.value)
                .isEmpty(),
        )
        assertTrue(
            database.liveOrganizationDao()
                .getManualPlacementOverrides(sourceId.value)
                .isEmpty(),
        )

        reconcile(generation = 2)

        assertTrue(
            database.liveOrganizationDao()
                .getOwnPlayCategoriesForCompatibility(sourceId.value)
                .isEmpty(),
        )
        assertTrue(
            database.liveOrganizationDao()
                .getManualPlacementOverrides(sourceId.value)
                .isEmpty(),
        )
    }

    @Test
    fun hiddenChannelIsExcludedFromProviderAndFavoritesButFavoriteSurvivesShow() = runBlocking {
        assertTrue(repository.setFavorite(sourceId, "a", true))
        assertTrue(repository.setProviderChannelHidden(sourceId, "al-news", "a", true))

        assertFalse(repository.observeFavoriteChannelIds(sourceId).first().contains("a"))
        assertFalse(repository.observeProviderCatalog(sourceId).first().channels.any { it.channelId == "a" })

        assertTrue(repository.setChannelHidden(sourceId, "a", false))
        assertTrue(repository.observeFavoriteChannelIds(sourceId).first().contains("a"))
        assertTrue(database.liveOrganizationDao().getChannelPersonalization("a")?.favorite == true)
    }

    @Test
    fun manualCategoryOrderOverridesProviderDisplayOrderUntilReset() = runBlocking {
        assertTrue(repository.setProviderCategoryOrder(sourceId, listOf("it-sports", "al-news")))
        assertTrue(repository.setProviderCategoryHidden(sourceId, "it-sports", true))
        assertTrue(repository.showAllProviderCategories(sourceId))

        val managed = repository.observeProviderManagement(sourceId).first()
        assertEquals(listOf("it-sports", "al-news"), managed.categories.map { it.categoryId })
        assertEquals(
            listOf("it-sports", "al-news"),
            repository.observeProviderCatalog(sourceId).first().categories.map { it.categoryId },
        )
        assertTrue(managed.categories.none { it.hidden })

        assertTrue(repository.resetProviderCategoryOrder(sourceId))
        assertEquals(
            listOf("al-news", "it-sports"),
            repository.observeProviderManagement(sourceId).first().categories.map { it.categoryId },
        )
    }

    @Test
    fun manualOrderSurvivesProviderRefreshUntilReset() = runBlocking {
        assertTrue(repository.setProviderCategoryOrder(sourceId, listOf("it-sports", "al-news")))
        assertTrue(repository.setProviderChannelOrder(sourceId, "al-news", listOf("b", "a")))

        database.refreshStateDao().upsertCategories(
            listOf(
                category("al-news", "AL News", 0),
                category("it-sports", "IT Sports", 5),
            ),
        )
        database.refreshStateDao().upsertLiveChannels(
            listOf(
                channel("a", "al-news", "AL News A", 0),
                channel("b", "al-news", "AL News B", 4),
                channel("c", "it-sports", "IT Sport C", 0),
            ),
        )
        reconcile(generation = 2)

        val afterRefresh = repository.observeProviderManagement(sourceId).first()
        assertEquals(
            listOf("it-sports", "al-news"),
            afterRefresh.categories.map { it.categoryId },
        )
        assertEquals(
            listOf("b", "a"),
            afterRefresh.channels
                .filter { it.categoryId == "al-news" }
                .map { it.channelId },
        )

        assertTrue(repository.resetProviderCategoryOrder(sourceId))
        assertTrue(repository.resetProviderChannelOrder(sourceId, "al-news"))

        val reset = repository.observeProviderManagement(sourceId).first()
        assertEquals(
            listOf("al-news", "it-sports"),
            reset.categories.map { it.categoryId },
        )
        assertEquals(
            listOf("a", "b"),
            reset.channels
                .filter { it.categoryId == "al-news" }
                .map { it.channelId },
        )
    }

    @Test
    fun channelOrderDoesNotFollowChannelIntoDifferentProviderCategory() = runBlocking {
        assertTrue(repository.setProviderChannelOrder(sourceId, "al-news", listOf("b", "a")))
        assertEquals(
            null,
            database.liveOrganizationDao().getChannelPersonalization("a")?.manualOrder,
        )

        database.refreshStateDao().upsertLiveChannels(
            listOf(
                channel("a", "it-sports", "AL News A", 1),
                channel("b", "al-news", "AL News B", 0),
                channel("c", "it-sports", "IT Sport C", 0),
            ),
        )
        reconcile(generation = 2)

        val movedCategoryChannels = repository.observeProviderManagement(sourceId).first()
            .channels.filter { it.categoryId == "it-sports" }

        assertEquals(listOf("c", "a"), movedCategoryChannels.map { it.channelId })
        assertEquals(
            null,
            movedCategoryChannels.first { it.channelId == "a" }.manualOrder,
        )
    }

    @Test
    fun manualChannelOrderOverridesProviderDisplayOrderUntilReset() = runBlocking {
        assertTrue(repository.setProviderChannelOrder(sourceId, "al-news", listOf("b", "a")))
        assertTrue(repository.setFavorite(sourceId, "a", true))
        assertEquals(
            listOf("b", "a"),
            repository.observeProviderManagement(sourceId).first()
                .channels.filter { it.categoryId == "al-news" }
                .map { it.channelId },
        )
        assertEquals(
            listOf("b", "a"),
            repository.observeProviderCatalog(sourceId).first()
                .channels.filter { it.providerCategoryId == "al-news" }
                .map { it.channelId },
        )

        assertTrue(repository.setProviderChannelHidden(sourceId, "al-news", "b", true))
        assertTrue(repository.setFavorite(sourceId, "b", true))
        val managed = repository.observeProviderManagement(sourceId).first()
            .channels.first { it.channelId == "b" }
        assertTrue(managed.hidden)
        assertTrue(managed.favorite)
        assertEquals(0, managed.manualOrder)

        assertTrue(repository.resetProviderChannelOrder(sourceId, "al-news"))
        val reset = repository.observeProviderManagement(sourceId).first()
            .channels.filter { it.categoryId == "al-news" }
        assertEquals(listOf("a", "b"), reset.map { it.channelId })
        assertTrue(reset.first { it.channelId == "b" }.hidden)
        assertTrue(reset.first { it.channelId == "b" }.favorite)
    }

    private suspend fun reconcile(generation: Long) {
        refreshStore.reconcileAutomatic(
            sourceId = sourceId,
            generation = generation,
            providerCategories = database.refreshStateDao().getCategoriesForRefresh(sourceId.value),
            liveChannels = database.refreshStateDao().getLiveChannelsForRefresh(sourceId.value),
        )
    }

    private fun category(key: String, name: String, order: Int) = ProviderCategoryEntity(
        sourceId = sourceId.value,
        kind = "LIVE",
        categoryKey = key,
        providerKey = key,
        name = name,
        providerOrder = order,
        available = true,
        lastSeenGeneration = 1,
    )

    private fun channel(id: String, categoryKey: String, name: String, order: Int) = LiveChannelEntity(
        channelId = id,
        sourceId = sourceId.value,
        providerKey = id,
        providerStreamId = id,
        categoryKey = categoryKey,
        name = name,
        tvgId = null,
        tvgName = null,
        logoUrl = null,
        streamLocator = "opaque://$id",
        providerOrder = order,
        available = true,
        lastSeenGeneration = 1,
    )
}
