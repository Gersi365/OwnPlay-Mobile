package app.ownplay.mobile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.sources.data.CatalogSection
import app.ownplay.mobile.sources.data.ProviderCatalogSnapshot
import app.ownplay.mobile.sources.data.ProviderCategoryRecord
import app.ownplay.mobile.sources.data.ProviderLiveChannelRecord
import app.ownplay.mobile.sources.data.ProviderMovieRecord
import app.ownplay.mobile.sources.data.ProviderSeriesRecord
import app.ownplay.mobile.sources.data.RoomCatalogRefreshStore
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartialCatalogRefreshInstrumentedTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var store: RoomCatalogRefreshStore
    private val sourceId = SourceId("partial-refresh-qa")

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OwnPlayDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomCatalogRefreshStore(database, database.refreshStateDao())
        database.sourceDao().insert(
            SourceEntity(
                sourceId = sourceId.value,
                displayName = "Partial QA",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.invalid",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun partialLiveRefreshPreservesCachedVodAndSeries() = runBlocking {
        store.commitSuccessfulRefresh(
            sourceId, SourceType.XTREAM, 1L, fullSnapshot(), 10L, 11L,
        )
        store.commitSuccessfulRefresh(
            sourceId, SourceType.XTREAM, 2L, liveOnlySnapshot(), 20L, 21L,
        )

        val dao = database.refreshStateDao()
        val movies = dao.getMoviesForRefresh(sourceId.value)
        val series = dao.getSeriesForRefresh(sourceId.value)
        val channels = dao.getLiveChannelsForRefresh(sourceId.value)

        assertEquals(1, movies.size)
        assertTrue(movies.single().available)
        assertEquals(1L, movies.single().lastSeenGeneration)
        assertEquals(1, series.size)
        assertTrue(series.single().available)
        assertEquals(1L, series.single().lastSeenGeneration)
        assertEquals(2, channels.size)
        assertTrue(channels.first { it.providerStreamId == "42" }.available)
        assertFalse(channels.first { it.providerStreamId == "43" }.available)
    }

    private fun fullSnapshot() = ProviderCatalogSnapshot(
        sourceType = SourceType.XTREAM,
        categories = listOf(
            ProviderCategoryRecord("LIVE", "7", "News", 0),
            ProviderCategoryRecord("MOVIE", "20", "Movies", 0),
            ProviderCategoryRecord("SERIES", "30", "Series", 0),
        ),
        liveChannels = listOf(live("42", 0), live("43", 1)),
        movies = listOf(
            ProviderMovieRecord("movie-100", "100", "20", "Movie", null, null, "mp4", null, 0),
        ),
        series = listOf(
            ProviderSeriesRecord("series-200", "200", "30", "Series", null, null, null, null, 0),
        ),
    )

    private fun liveOnlySnapshot() = ProviderCatalogSnapshot(
        sourceType = SourceType.XTREAM,
        categories = listOf(ProviderCategoryRecord("LIVE", "7", "News Updated", 0)),
        liveChannels = listOf(live("42", 0)),
        movies = emptyList(),
        series = emptyList(),
        authoritativeSections = setOf(
            CatalogSection.LIVE_CATEGORIES,
            CatalogSection.LIVE_CHANNELS,
        ),
    )

    private fun live(id: String, order: Int) = ProviderLiveChannelRecord(
        proposedChannelId = "channel-$id",
        providerKey = id,
        providerStreamId = id,
        categoryProviderKey = "7",
        name = "Channel $id",
        tvgId = null,
        tvgName = null,
        logoUrl = null,
        streamLocator = "xtream://live/$id?ext=ts",
        providerOrder = order,
    )
}
