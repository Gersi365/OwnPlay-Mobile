package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagedDownloadRepositoryTest {
    @Test
    fun pauseShowsResumeControlAndResumeRestartsManagedWork() = runBlocking {
        val store = DownloadTestStore()
        val scheduler = FakeScheduler()
        val notifications = RecordingNotifications()
        val repository = managed(
            store = store,
            scheduler = scheduler,
            available = { true },
            notifications = notifications,
        )

        val queued = repository.enqueue(store.request)
        assertTrue(repository.markDownloading(queued.downloadId))
        notifications.cancelledResults.clear()

        assertTrue(repository.pause(queued.downloadId))
        assertEquals(
            listOf(queued.downloadId),
            notifications.pausedItems.map { it.downloadId },
        )
        assertEquals(DownloadStatus.PAUSED, repository.get(queued.downloadId)?.status)

        assertTrue(repository.resume(queued.downloadId))
        assertEquals(listOf(queued.downloadId), notifications.cancelledResults)
        assertEquals(2, scheduler.enqueued.count { it == queued.downloadId })
        assertEquals(DownloadStatus.QUEUED, repository.get(queued.downloadId)?.status)
    }

    @Test
    fun destinationIsPinnedUntilTheDownloadRecordIsRemoved() = runBlocking {
        val store = DownloadTestStore()
        val scheduler = FakeScheduler()
        val preferences = FakePreferencesRepository("Download/Old/")
        val assignments = FakeDestinationAssignments()
        val repository = managed(
            store = store,
            scheduler = scheduler,
            available = { true },
            preferences = preferences,
            assignments = assignments,
        )

        val first = repository.enqueue(store.request)
        assertEquals("Download/Old/", assignments.values[first.downloadId])

        assertTrue(store.repository.fail(first.downloadId, DownloadFailureCode.NETWORK))
        preferences.setDestinationRelativePath("Download/New/")
        val retried = repository.enqueue(store.request)
        assertEquals(DownloadStatus.QUEUED, retried.status)
        assertEquals("Download/Old/", assignments.values[first.downloadId])

        assertTrue(repository.remove(first.downloadId))
        val newDownload = repository.enqueue(store.request)
        assertEquals("Download/New/", assignments.values[newDownload.downloadId])
    }

    @Test
    fun missingCompletedOutputBecomesRetryableMissingState() = runBlocking {
        val store = DownloadTestStore()
        val completed = store.completed()
        val scheduler = FakeScheduler()
        val repository = managed(store, scheduler, available = { false })

        val reconciled = requireNotNull(repository.get(completed.downloadId))
        assertEquals(DownloadStatus.MISSING, reconciled.status)
        assertEquals(DownloadFailureCode.INTEGRITY, reconciled.failureCode)

        val retried = repository.enqueue(store.request)
        assertEquals(DownloadStatus.QUEUED, retried.status)
        assertEquals(listOf(retried.downloadId), scheduler.enqueued)
    }

    @Test
    fun completedOutputIsReprobedSoExternalDeletionIsDetected() = runBlocking {
        val store = DownloadTestStore()
        val completed = store.completed()
        var probes = 0
        val repository = managed(
            store,
            FakeScheduler(),
            available = {
                probes += 1
                probes == 1
            },
        )

        assertEquals(DownloadStatus.COMPLETED, repository.get(completed.downloadId)?.status)
        val missing = requireNotNull(repository.get(completed.downloadId))
        assertEquals(DownloadStatus.MISSING, missing.status)
        assertEquals(DownloadFailureCode.INTEGRITY, missing.failureCode)
        assertEquals(2, probes)
    }

    private fun managed(
        store: DownloadTestStore,
        scheduler: FakeScheduler,
        available: suspend () -> Boolean,
        preferences: FakePreferencesRepository = FakePreferencesRepository(
            "Download/OwnPlay Downloads/",
        ),
        assignments: FakeDestinationAssignments = FakeDestinationAssignments(),
        notifications: DownloadNotificationEvents = FakeNotifications,
    ) = WorkManagedDownloadRepository(
        delegate = store.repository,
        scheduler = scheduler,
        storage = FakeStorage,
        notifications = notifications,
        availabilityProbe = DownloadedMediaAvailabilityProbe { available() },
        preferencesRepository = preferences,
        destinationAssignments = assignments,
    )

    private class FakePreferencesRepository(
        destination: String,
    ) : DownloadPreferencesRepository {
        private val state = MutableStateFlow(
            DownloadPreferences(destinationRelativePath = destination),
        )

        override val preferences: Flow<DownloadPreferences> = state

        override suspend fun current(): DownloadPreferences = state.value

        override suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean {
            state.value = state.value.copy(unmeteredNetworkOnly = enabled)
            return true
        }

        override suspend fun setDestinationRelativePath(relativePath: String): Boolean {
            state.value = state.value.copy(destinationRelativePath = relativePath)
            return true
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean): Boolean {
            state.value = state.value.copy(notificationsEnabled = enabled)
            return true
        }
    }

    private class FakeDestinationAssignments : DownloadDestinationAssignmentStore {
        val values = mutableMapOf<DownloadId, String>()

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

    private class FakeScheduler : DownloadWorkScheduler {
        val enqueued = mutableListOf<DownloadId>()
        override suspend fun enqueue(downloadId: DownloadId, replace: Boolean) {
            enqueued += downloadId
        }
        override fun cancel(downloadId: DownloadId) = Unit
    }

    private class RecordingNotifications : DownloadNotificationEvents {
        val pausedItems = mutableListOf<app.ownplay.mobile.downloads.domain.DownloadItem>()
        val cancelledResults = mutableListOf<DownloadId>()

        override fun showPaused(item: app.ownplay.mobile.downloads.domain.DownloadItem) {
            pausedItems += item
        }

        override fun cancelResult(downloadId: DownloadId) {
            cancelledResults += downloadId
        }

        override fun cancelAll(downloadId: DownloadId) = Unit
    }

    private object FakeNotifications : DownloadNotificationEvents {
        override fun cancelResult(downloadId: DownloadId) = Unit
        override fun cancelAll(downloadId: DownloadId) = Unit
    }

    private object FakeStorage : DownloadStorage {
        override suspend fun openPending(downloadId: DownloadId, media: ResolvedDownloadMedia): PendingDownloadOutput? = null
        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = null
        override suspend fun publish(pending: PendingDownloadOutput): String? = null
        override suspend fun discard(pending: PendingDownloadOutput) = Unit
        override suspend fun discardPending(downloadId: DownloadId): Boolean = true
        override suspend fun removePublished(localReference: String): Boolean = true
    }
}
