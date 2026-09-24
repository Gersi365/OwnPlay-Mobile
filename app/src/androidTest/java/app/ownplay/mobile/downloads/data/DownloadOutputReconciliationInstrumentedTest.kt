package app.ownplay.mobile.downloads.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadOutputReconciliationInstrumentedTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var outputFile: File
    private val sourceId = SourceId("download-reconcile-qa")

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, OwnPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.sourceDao().insert(
            SourceEntity(
                sourceId = sourceId.value,
                displayName = "Download QA",
                type = "XTREAM",
                baseLocator = "https://example.invalid",
                credentialReference = sourceId.value,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        outputFile = File(context.filesDir, "ownplay-downloads/completed/runtime-delete.mp4")
        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        Unit
    }

    @After
    fun tearDown() {
        outputFile.delete()
        database.close()
    }

    @Test
    fun externalDeletionBecomesRetryableWithoutChangingStableIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var clock = 10L
        val delegate = RoomDownloadRepository(database.downloadDao()) { clock++ }
        val scheduler = RecordingScheduler()
        val repository = WorkManagedDownloadRepository(
            delegate = delegate,
            scheduler = scheduler,
            storage = NoOpStorage,
            notifications = NoOpNotifications,
            availabilityProbe = AndroidDownloadedMediaVerifier(context),
            preferencesRepository = StaticDownloadPreferences,
            destinationAssignments = InMemoryDestinationAssignments(),
        )
        val request = DownloadRequest(sourceId, DownloadMediaKind.MOVIE, "movie-42", "Movie 42")
        val queued = delegate.enqueue(request)
        assertTrue(delegate.markDownloading(queued.downloadId))
        outputFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(delegate.complete(queued.downloadId, outputFile.toURI().toString(), 4L, null))

        assertEquals(DownloadStatus.COMPLETED, repository.get(queued.downloadId)?.status)
        assertTrue(outputFile.delete())
        val missing = requireNotNull(repository.get(queued.downloadId))
        assertEquals(DownloadStatus.MISSING, missing.status)

        val retried = repository.enqueue(request)
        assertEquals(queued.downloadId, retried.downloadId)
        assertEquals(DownloadStatus.QUEUED, retried.status)
        assertEquals(listOf(queued.downloadId), scheduler.enqueued)
        assertFalse(outputFile.exists())
    }

    private object StaticDownloadPreferences : DownloadPreferencesRepository {
        private val value = DownloadPreferences()

        override val preferences: Flow<DownloadPreferences> = flowOf(value)

        override suspend fun current(): DownloadPreferences = value

        override suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean = false

        override suspend fun setDestinationRelativePath(relativePath: String): Boolean = false

        override suspend fun setNotificationsEnabled(enabled: Boolean): Boolean = false
    }

    private class InMemoryDestinationAssignments : DownloadDestinationAssignmentStore {
        private val values = mutableMapOf<DownloadId, String>()

        override suspend fun get(downloadId: DownloadId): String? = values[downloadId]

        override suspend fun pin(
            downloadId: DownloadId,
            destinationRelativePath: String,
        ): Boolean {
            values.putIfAbsent(downloadId, destinationRelativePath)
            return true
        }

        override suspend fun remove(downloadId: DownloadId): Boolean {
            values.remove(downloadId)
            return true
        }
    }

    private class RecordingScheduler : DownloadWorkScheduler {
        val enqueued = mutableListOf<DownloadId>()
        override suspend fun enqueue(downloadId: DownloadId, replace: Boolean) {
            enqueued += downloadId
        }
        override fun cancel(downloadId: DownloadId) = Unit
    }

    private object NoOpNotifications : DownloadNotificationEvents {
        override fun cancelResult(downloadId: DownloadId) = Unit
        override fun cancelAll(downloadId: DownloadId) = Unit
    }

    private object NoOpStorage : DownloadStorage {
        override suspend fun openPending(downloadId: DownloadId, media: ResolvedDownloadMedia): PendingDownloadOutput? = null
        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = null
        override suspend fun publish(pending: PendingDownloadOutput): String? = null
        override suspend fun discard(pending: PendingDownloadOutput) = Unit
        override suspend fun discardPending(downloadId: DownloadId): Boolean = true
        override suspend fun removePublished(localReference: String): Boolean = true
    }
}
