package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedSourceRemovalDownloadCoordinatorTest {
    @Test
    fun quiesceAttemptsEveryDownloadAndReportsPartialFailure() = runBlocking {
        val scheduler = RecordingScheduler(failCancelFor = setOf("download:a"))
        val coordinator = coordinator(
            downloadDao = FakeDownloadDao(),
            scheduler = scheduler,
        )
        val plan = SourceRemovalDownloadPlan(
            listOf(DownloadId("download:a"), DownloadId("download:b")),
        )

        assertFalse(coordinator.quiesce(plan))
        assertEquals(listOf("download:a", "download:b"), scheduler.cancelled)
    }

    @Test
    fun restoreContinuesAfterOneDownloadFailsToReenqueue() = runBlocking {
        val scheduler = RecordingScheduler(failEnqueueFor = setOf("download:a"))
        val downloadDao = FakeDownloadDao(
            rows = mapOf(
                "download:a" to download("download:a", DownloadStatus.QUEUED),
                "download:b" to download("download:b", DownloadStatus.DOWNLOADING),
            ),
        )
        val coordinator = coordinator(
            downloadDao = downloadDao,
            scheduler = scheduler,
        )
        val plan = SourceRemovalDownloadPlan(
            listOf(DownloadId("download:a"), DownloadId("download:b")),
        )

        coordinator.restore(plan)

        assertEquals(listOf("download:a", "download:b"), scheduler.enqueued)
    }

    @Test
    fun finalizeContinuesAcrossCleanupFailuresAndRemainingDownloads() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = ManagedSourceRemovalDownloadCoordinator(
            downloadDao = FakeDownloadDao(),
            scheduler = RecordingScheduler(),
            storage = RecordingStorage(events, failFor = setOf("download:a")),
            notifications = RecordingNotifications(events, failFor = setOf("download:a")),
            destinationAssignments = RecordingDestinationAssignments(
                events,
                failFor = setOf("download:a"),
            ),
        )
        val plan = SourceRemovalDownloadPlan(
            listOf(DownloadId("download:a"), DownloadId("download:b")),
        )

        coordinator.finalize(plan)

        assertEquals(
            listOf(
                "discard:download:a",
                "notify:download:a",
                "destination:download:a",
                "discard:download:b",
                "notify:download:b",
                "destination:download:b",
            ),
            events,
        )
    }

    private fun coordinator(
        downloadDao: DownloadDao,
        scheduler: DownloadWorkScheduler,
    ) = ManagedSourceRemovalDownloadCoordinator(
        downloadDao = downloadDao,
        scheduler = scheduler,
        storage = RecordingStorage(mutableListOf()),
        notifications = RecordingNotifications(mutableListOf()),
        destinationAssignments = RecordingDestinationAssignments(mutableListOf()),
    )

    private fun download(
        downloadId: String,
        status: DownloadStatus,
    ) = DownloadEntity(
        downloadId = downloadId,
        sourceId = "source-a",
        mediaKind = "MOVIE",
        contentId = downloadId,
        title = downloadId,
        streamIdentity = "opaque:" + downloadId,
        state = status.name,
        bytesDownloaded = 0L,
        totalBytes = null,
        localReference = null,
        integrityMetadata = null,
        failureReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private class RecordingScheduler(
    private val failCancelFor: Set<String> = emptySet(),
    private val failEnqueueFor: Set<String> = emptySet(),
) : DownloadWorkScheduler {
    val cancelled = mutableListOf<String>()
    val enqueued = mutableListOf<String>()

    override suspend fun enqueue(downloadId: DownloadId, replace: Boolean) {
        enqueued += downloadId.value
        if (downloadId.value in failEnqueueFor) error("enqueue failed")
    }

    override fun cancel(downloadId: DownloadId) {
        cancelled += downloadId.value
        if (downloadId.value in failCancelFor) error("cancel failed")
    }
}

private class RecordingStorage(
    private val events: MutableList<String>,
    private val failFor: Set<String> = emptySet(),
) : DownloadStorage {
    override suspend fun openPending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
    ): PendingDownloadOutput? = error("unused")

    override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = error("unused")

    override suspend fun publish(pending: PendingDownloadOutput): String? = error("unused")

    override suspend fun discard(pending: PendingDownloadOutput) = error("unused")

    override suspend fun discardPending(downloadId: DownloadId): Boolean {
        events += "discard:" + downloadId.value
        if (downloadId.value in failFor) error("discard failed")
        return true
    }

    override suspend fun removePublished(localReference: String): Boolean = error("unused")
}

private class RecordingNotifications(
    private val events: MutableList<String>,
    private val failFor: Set<String> = emptySet(),
) : DownloadNotificationEvents {
    override fun cancelResult(downloadId: DownloadId) = Unit

    override fun cancelAll(downloadId: DownloadId) {
        events += "notify:" + downloadId.value
        if (downloadId.value in failFor) error("notification cleanup failed")
    }
}

private class RecordingDestinationAssignments(
    private val events: MutableList<String>,
    private val failFor: Set<String> = emptySet(),
) : DownloadDestinationAssignmentStore {
    override suspend fun get(downloadId: DownloadId): String? = null

    override suspend fun pin(
        downloadId: DownloadId,
        destinationRelativePath: String,
    ): Boolean = true

    override suspend fun remove(downloadId: DownloadId): Boolean {
        events += "destination:" + downloadId.value
        if (downloadId.value in failFor) error("destination cleanup failed")
        return true
    }
}

private class FakeDownloadDao(
    private val rows: Map<String, DownloadEntity> = emptyMap(),
) : DownloadDao {
    override fun observeForSource(sourceId: String): Flow<List<DownloadEntity>> = flowOf(emptyList())

    override fun observe(downloadId: String): Flow<DownloadEntity?> = flowOf(rows[downloadId])

    override suspend fun get(downloadId: String): DownloadEntity? = rows[downloadId]

    override suspend fun getExecutionQueue(): List<DownloadEntity> = emptyList()

    override suspend fun getIdsForSource(sourceId: String): List<String> =
        rows.values.filter { it.sourceId == sourceId }.map { it.downloadId }

    override suspend fun getForContent(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): DownloadEntity? = rows.values.firstOrNull {
        it.sourceId == sourceId && it.mediaKind == mediaKind && it.contentId == contentId
    }

    override suspend fun upsert(entity: DownloadEntity) = error("unused")

    override suspend fun delete(downloadId: String): Int = error("unused")
}
