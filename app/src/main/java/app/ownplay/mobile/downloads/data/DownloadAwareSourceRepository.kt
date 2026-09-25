package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRepository

internal data class SourceRemovalDownloadPlan(
    val downloadIds: List<DownloadId>,
    val publishedReferences: List<String> = emptyList(),
)

internal interface SourceRemovalDownloadCoordinator {
    suspend fun capture(sourceId: SourceId): SourceRemovalDownloadPlan?
    suspend fun quiesce(plan: SourceRemovalDownloadPlan): Boolean
    suspend fun restore(plan: SourceRemovalDownloadPlan)
    suspend fun finalize(plan: SourceRemovalDownloadPlan)
}

internal class ManagedSourceRemovalDownloadCoordinator(
    private val downloadDao: DownloadDao,
    private val scheduler: DownloadWorkScheduler,
    private val storage: DownloadStorage,
    private val notifications: DownloadNotificationEvents,
    private val destinationAssignments: DownloadDestinationAssignmentStore,
) : SourceRemovalDownloadCoordinator {
    override suspend fun capture(sourceId: SourceId): SourceRemovalDownloadPlan? =
        runCatching {
            val downloadIds = downloadDao.getIdsForSource(sourceId.value).map(::DownloadId)
            val publishedReferences = downloadIds.mapNotNull { downloadId ->
                val row = downloadDao.get(downloadId.value) ?: return@mapNotNull null
                row.localReference
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf { row.state == DownloadStatus.COMPLETED.name }
            }.distinct()
            SourceRemovalDownloadPlan(
                downloadIds = downloadIds,
                publishedReferences = publishedReferences,
            )
        }.getOrNull()

    override suspend fun quiesce(plan: SourceRemovalDownloadPlan): Boolean {
        var allCancelled = true
        plan.downloadIds.forEach { downloadId ->
            if (runCatching { scheduler.cancel(downloadId) }.isFailure) {
                allCancelled = false
            }
        }
        return allCancelled
    }

    override suspend fun restore(plan: SourceRemovalDownloadPlan) {
        plan.downloadIds.forEach { downloadId ->
            runCatching {
                val state = downloadDao.get(downloadId.value)?.state ?: return@runCatching
                if (
                    state == DownloadStatus.WAITING_FOR_WIFI.name ||
                    state == DownloadStatus.QUEUED.name ||
                    state == DownloadStatus.DOWNLOADING.name
                ) {
                    scheduler.enqueue(downloadId, replace = true)
                }
            }
        }
    }

    override suspend fun finalize(plan: SourceRemovalDownloadPlan) {
        plan.downloadIds.forEach { downloadId ->
            runCatching { storage.discardPending(downloadId) }
            runCatching { notifications.cancelAll(downloadId) }
            runCatching { destinationAssignments.remove(downloadId) }
        }
        plan.publishedReferences.forEach { localReference ->
            runCatching { storage.removePublished(localReference) }
        }
    }
}

internal class DownloadAwareSourceRepository(
    private val delegate: SourceRepository,
    private val removalCoordinator: SourceRemovalDownloadCoordinator,
) : SourceRepository by delegate {
    override suspend fun removeSource(sourceId: SourceId): Boolean {
        val plan = removalCoordinator.capture(sourceId) ?: return false
        if (!removalCoordinator.quiesce(plan)) {
            runCatching { removalCoordinator.restore(plan) }
            return false
        }

        if (!delegate.removeSource(sourceId)) {
            runCatching { removalCoordinator.restore(plan) }
            return false
        }

        runCatching { removalCoordinator.finalize(plan) }
        return true
    }
}
