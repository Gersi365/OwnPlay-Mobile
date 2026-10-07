package app.ownplay.mobile.feature.live.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveGuideProgramEntity
import app.ownplay.mobile.data.db.LiveGuideSnapshotEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.ProviderTransport
import app.ownplay.mobile.sources.data.ProviderTransportException
import app.ownplay.mobile.sources.data.ProviderTransportFailureCategory
import app.ownplay.mobile.sources.data.ProviderResponse
import app.ownplay.mobile.sources.data.m3u.M3uXmltvClient
import app.ownplay.mobile.sources.data.m3u.M3uXmltvGuide
import app.ownplay.mobile.sources.data.m3u.M3uXmltvProgram
import app.ownplay.mobile.sources.data.xtream.OkHttpXtreamClient
import app.ownplay.mobile.sources.domain.SourceId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceBackedLiveGuidePersistenceInstrumentedTest {

    @Test
    fun staleScheduleSurvivesDatabaseRecreationAndProviderFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = databaseName()
        val sourceId = SourceId("epg-source")
        val channelId = "epg-channel"
        val fetchedAt = NOW_MS - 6 * 60 * 1_000L
        var database = openDatabase(context, name)
        try {
            seedSourceAndChannel(database, sourceId, channelId)
            database.liveGuideDao().replaceSnapshot(
                snapshot(sourceId, channelId, fetchedAt, isEmpty = false),
                listOf(programRow(sourceId, channelId, "last-good-program", "Last good guide")),
            )
            database.close()

            database = openDatabase(context, name)
            val client = FakeXmltvClient { throw ProviderTransportException(ProviderTransportFailureCategory.NETWORK) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val repository = repository(database, sourceId, client, scope)

                val schedule = repository.loadSchedule(sourceId, channelId)

                assertEquals(listOf("Last good guide"), schedule.map { it.title })
                assertEquals(1, client.calls.get())
                assertEquals(fetchedAt, database.liveGuideDao()
                    .getSnapshot(sourceId.value, channelId, FULL_SNAPSHOT)?.fetchedAtEpochMs)
                assertEquals(
                    listOf("Last good guide"),
                    database.liveGuideDao().getPrograms(sourceId.value, channelId, FULL_SNAPSHOT)
                        .map { it.title },
                )
            } finally {
                scope.cancel()
            }
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun successfulEmptyGuideIsPersistedSeparatelyFromProviderFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = databaseName()
        val sourceId = SourceId("empty-epg-source")
        val channelId = "empty-epg-channel"
        var database = openDatabase(context, name)
        try {
            seedSourceAndChannel(database, sourceId, channelId)
            val successScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val successRepository = repository(
                    database,
                    sourceId,
                    FakeXmltvClient { emptyList() },
                    successScope,
                )
                assertTrue(successRepository.loadSchedule(sourceId, channelId).isEmpty())
            } finally {
                successScope.cancel()
            }
            assertTrue(database.liveGuideDao()
                .getSnapshot(sourceId.value, channelId, FULL_SNAPSHOT)!!.isEmpty)
            assertTrue(database.liveGuideDao()
                .getPrograms(sourceId.value, channelId, FULL_SNAPSHOT).isEmpty())

            database.close()
            database = openDatabase(context, name)
            val failureScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val failureClient = FakeXmltvClient {
                    throw ProviderTransportException(ProviderTransportFailureCategory.NETWORK)
                }
                val failureRepository = repository(database, sourceId, failureClient, failureScope)

                assertTrue(failureRepository.loadSchedule(sourceId, channelId).isEmpty())
                assertEquals(0, failureClient.calls.get())
                assertNotNull(database.liveGuideDao()
                    .getSnapshot(sourceId.value, channelId, FULL_SNAPSHOT))
            } finally {
                failureScope.cancel()
            }
        } finally {
            if (database.isOpen) database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletingSourceCascadesItsPersistedGuide() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = databaseName()
        val sourceId = SourceId("deleted-epg-source")
        val channelId = "deleted-epg-channel"
        val database = openDatabase(context, name)
        try {
            seedSourceAndChannel(database, sourceId, channelId)
            database.liveGuideDao().replaceSnapshot(
                snapshot(sourceId, channelId, NOW_MS, isEmpty = false),
                listOf(programRow(sourceId, channelId, "delete-program", "Delete me")),
            )

            database.sourceDao().delete(sourceId.value)

            assertNull(database.liveGuideDao().getSnapshot(sourceId.value, channelId, FULL_SNAPSHOT))
            assertTrue(database.liveGuideDao().getPrograms(sourceId.value, channelId, FULL_SNAPSHOT).isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seedSourceAndChannel(
        database: OwnPlayDatabase,
        sourceId: SourceId,
        channelId: String,
    ) {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = sourceId.value,
                displayName = "Guide source",
                type = "M3U",
                baseLocator = "https://provider.invalid/list.m3u",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.refreshStateDao().upsertLiveChannels(
            listOf(
                LiveChannelEntity(
                    channelId = channelId,
                    sourceId = sourceId.value,
                    providerKey = channelId,
                    providerStreamId = null,
                    categoryKey = null,
                    name = "Guide channel",
                    tvgId = "guide-channel",
                    tvgName = "Guide channel",
                    logoUrl = null,
                    streamLocator = "https://provider.invalid/live.m3u8",
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 1L,
                ),
            ),
        )
    }

    private fun repository(
        database: OwnPlayDatabase,
        sourceId: SourceId,
        client: FakeXmltvClient,
        scope: CoroutineScope,
    ) = SourceBackedLiveGuideRepository(
        sourceDao = database.sourceDao(),
        liveOrganizationDao = database.liveOrganizationDao(),
        liveGuideDao = database.liveGuideDao(),
        credentialStore = object : CredentialStore {
            override suspend fun put(sourceId: SourceId, secret: SourceSecret) = Unit
            override suspend fun get(sourceId: SourceId): SourceSecret? =
                SourceSecret.M3uRemote("https://provider.invalid/list.m3u", "https://provider.invalid/guide.xml")
            override suspend fun delete(sourceId: SourceId) = Unit
        },
        xtreamClient = OkHttpXtreamClient(
            object : ProviderTransport {
                override suspend fun get(url: String): ProviderResponse =
                    error("M3U source must not call Xtream")
            },
        ),
        m3uXmltvClient = client,
        nowMillis = { NOW_MS },
        refreshScope = scope,
    )

    private fun openDatabase(context: Context, name: String) =
        Room.databaseBuilder(context, OwnPlayDatabase::class.java, name).build()

    private fun snapshot(
        sourceId: SourceId,
        channelId: String,
        fetchedAt: Long,
        isEmpty: Boolean,
    ) = LiveGuideSnapshotEntity(
        sourceId = sourceId.value,
        channelId = channelId,
        snapshotKind = FULL_SNAPSHOT,
        fetchedAtEpochMs = fetchedAt,
        isEmpty = isEmpty,
    )

    private fun programRow(
        sourceId: SourceId,
        channelId: String,
        key: String,
        title: String,
    ) = LiveGuideProgramEntity(
        sourceId = sourceId.value,
        channelId = channelId,
        snapshotKind = FULL_SNAPSHOT,
        programKey = key,
        title = title,
        startEpochSeconds = 1_800_000_000L,
        endEpochSeconds = 1_800_003_600L,
        providerOrder = 0,
    )

    private fun databaseName() = "ownplay-guide-${UUID.randomUUID()}.db"

    private companion object {
        const val FULL_SNAPSHOT = "FULL"
        const val NOW_MS = 1_800_000_000_000L
    }
}

private class FakeXmltvClient(
    private val load: suspend () -> List<M3uXmltvProgram>,
) : M3uXmltvClient {
    val calls = AtomicInteger()

    override suspend fun fetch(remoteUrl: String): M3uXmltvGuide = error("Channel-scoped fetch expected")

    override suspend fun fetchPrograms(remoteUrl: String, channelId: String): List<M3uXmltvProgram> {
        calls.incrementAndGet()
        return load()
    }
}
