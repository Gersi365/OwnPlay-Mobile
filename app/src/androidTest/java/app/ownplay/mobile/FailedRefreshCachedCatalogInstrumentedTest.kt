package app.ownplay.mobile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.CatalogLoadException
import app.ownplay.mobile.sources.data.ProviderCatalogSnapshot
import app.ownplay.mobile.sources.data.ProviderCategoryRecord
import app.ownplay.mobile.sources.data.ProviderLiveChannelRecord
import app.ownplay.mobile.sources.data.ProviderMovieRecord
import app.ownplay.mobile.sources.data.ProviderSeriesRecord
import app.ownplay.mobile.sources.data.RoomCatalogRefreshStore
import app.ownplay.mobile.sources.data.SourceCatalogLoader
import app.ownplay.mobile.sources.data.SourceRepositoryImpl
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailedRefreshCachedCatalogInstrumentedTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var store: RoomCatalogRefreshStore
    private val sourceId = SourceId("failed-refresh-cache-qa")

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
                displayName = "Failed refresh QA",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.invalid",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        store.commitSuccessfulRefresh(
            sourceId = sourceId,
            sourceType = SourceType.XTREAM,
            generation = 1L,
            snapshot = successfulSnapshot(),
            attemptAtEpochMs = 10L,
            completedAtEpochMs = 11L,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun failedRefreshLeavesPreviousSuccessfulCatalogVisibleInRoom() = runBlocking {
        val repository = SourceRepositoryImpl(
            sourceDao = database.sourceDao(),
            refreshStateDao = database.refreshStateDao(),
            activeSourceStore = FakeActiveSourceStore(sourceId.value),
            credentialStore = FakeCredentialStore(
                sourceId,
                SourceSecret.Xtream("user", "password"),
            ),
            catalogLoader = FailingCatalogLoader(SourceRefreshFailureCategory.TIMEOUT),
            catalogRefreshStore = store,
            nowMillis = { 20L },
        )

        val result = repository.refreshSource(sourceId)

        assertEquals(
            SourceRefreshResult.Failure(
                category = SourceRefreshFailureCategory.TIMEOUT,
                safeMessage = "Source request timed out.",
            ),
            result,
        )

        val dao = database.refreshStateDao()
        val channels = dao.getLiveChannelsForRefresh(sourceId.value)
        val movies = dao.getMoviesForRefresh(sourceId.value)
        val series = dao.getSeriesForRefresh(sourceId.value)

        assertEquals(listOf("Cached Live"), channels.filter { it.available }.map { it.name })
        assertEquals(listOf("Cached Movie"), movies.filter { it.available }.map { it.name })
        assertEquals(listOf("Cached Series"), series.filter { it.available }.map { it.name })
        assertTrue(channels.all { it.lastSeenGeneration == 1L })
        assertTrue(movies.all { it.lastSeenGeneration == 1L })
        assertTrue(series.all { it.lastSeenGeneration == 1L })

        val refreshState = dao.get(sourceId.value)!!
        assertEquals(1L, refreshState.generation)
        assertEquals("FAILED", refreshState.state)
        assertEquals(11L, refreshState.lastSuccess)
        assertEquals(SourceRefreshFailureCategory.TIMEOUT.name, refreshState.errorCode)
    }

    private fun successfulSnapshot() = ProviderCatalogSnapshot(
        sourceType = SourceType.XTREAM,
        categories = listOf(
            ProviderCategoryRecord("LIVE", "live", "Live", 0),
            ProviderCategoryRecord("MOVIE", "movie", "Movies", 0),
            ProviderCategoryRecord("SERIES", "series", "Series", 0),
        ),
        liveChannels = listOf(
            ProviderLiveChannelRecord(
                proposedChannelId = "cached-live",
                providerKey = "1",
                providerStreamId = "1",
                categoryProviderKey = "live",
                name = "Cached Live",
                tvgId = null,
                tvgName = null,
                logoUrl = null,
                streamLocator = "xtream://live/1?ext=ts",
                providerOrder = 0,
            ),
        ),
        movies = listOf(
            ProviderMovieRecord(
                proposedMovieId = "cached-movie",
                providerStreamId = "2",
                categoryProviderKey = "movie",
                name = "Cached Movie",
                posterUrl = null,
                backdropUrl = null,
                extension = "mp4",
                rating = null,
                providerOrder = 0,
            ),
        ),
        series = listOf(
            ProviderSeriesRecord(
                proposedSeriesId = "cached-series",
                providerSeriesId = "3",
                categoryProviderKey = "series",
                name = "Cached Series",
                posterUrl = null,
                backdropUrl = null,
                description = null,
                rating = null,
                providerOrder = 0,
            ),
        ),
    )
}

private class FakeActiveSourceStore(initial: String?) : ActiveSourceSelectionStore {
    private val state = MutableStateFlow(initial)
    override val selectedSourceId: Flow<String?> = state
    override suspend fun currentSelectedSourceId(): String? = state.value
    override suspend fun setSelectedSourceId(sourceId: String?) {
        state.value = sourceId
    }
}

private class FakeCredentialStore(
    private val expectedSourceId: SourceId,
    private val secret: SourceSecret,
) : CredentialStore {
    override suspend fun put(sourceId: SourceId, secret: SourceSecret) = Unit

    override suspend fun get(sourceId: SourceId): SourceSecret? =
        secret.takeIf { sourceId == expectedSourceId }

    override suspend fun delete(sourceId: SourceId) = Unit
}

private class FailingCatalogLoader(
    private val category: SourceRefreshFailureCategory,
) : SourceCatalogLoader {
    override suspend fun load(
        sourceId: SourceId,
        sourceType: SourceType,
        baseLocator: String,
        secret: SourceSecret,
    ): ProviderCatalogSnapshot {
        throw CatalogLoadException(category)
    }
}
