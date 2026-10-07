package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class SourceRemovalDownloadPlan(
    val downloadIds: List<DownloadId>,
)

internal interface SourceRemovalDownloadCoordinator {
    suspend fun capture(sourceId: SourceId): SourceRemovalDownloadPlan?
    suspend fun quiesce(plan: SourceRemovalDownloadPlan): Boolean
    suspend fun restore(plan: SourceRemovalDownloadPlan)
    suspend fun finalize(plan: SourceRemovalDownloadPlan)
    suspend fun sourceExists(sourceId: SourceId): Boolean = true
}

internal class ManagedSourceRemovalDownloadCoordinator(
    private val sourceDao: SourceDao,
    private val downloadDao: DownloadDao,
    private val scheduler: DownloadWorkScheduler,
    private val storage: DownloadStorage,
    private val notifications: DownloadNotificationEvents,
    private val destinationAssignments: DownloadDestinationAssignmentStore,
) : SourceRemovalDownloadCoordinator {
    override suspend fun capture(sourceId: SourceId): SourceRemovalDownloadPlan? =
        try {
            val downloadIds = downloadDao.getIdsForSource(sourceId.value).map(::DownloadId)
            SourceRemovalDownloadPlan(
                downloadIds = downloadIds,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    override suspend fun quiesce(plan: SourceRemovalDownloadPlan): Boolean {
        var allCancelled = true
        plan.downloadIds.forEach { downloadId ->
            val cancelled = try {
                scheduler.cancel(downloadId)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!cancelled) {
                allCancelled = false
            }
        }
        return allCancelled
    }

    override suspend fun restore(plan: SourceRemovalDownloadPlan) {
        plan.downloadIds.forEach { downloadId ->
            try {
                val state = downloadDao.get(downloadId.value)?.state ?: return@forEach
                if (
                    state == DownloadStatus.WAITING_FOR_WIFI.name ||
                    state == DownloadStatus.QUEUED.name ||
                    state == DownloadStatus.DOWNLOADING.name
                ) {
                    scheduler.enqueue(downloadId, replace = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue restoring other captured downloads.
            }
        }
    }

    override suspend fun finalize(plan: SourceRemovalDownloadPlan) {
        plan.downloadIds.forEach { downloadId ->
            try {
                storage.discardPending(downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue cleaning notifications and destination state.
            }
            try {
                notifications.cancelAll(downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Continue cleaning destination state.
            }
            try {
                destinationAssignments.remove(downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Remaining downloads are still finalized independently.
            }
        }
    }

    override suspend fun sourceExists(sourceId: SourceId): Boolean =
        try {
            sourceDao.get(sourceId.value) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
}

internal class DownloadAwareSourceRepository(
    private val delegate: SourceRepository,
    private val removalCoordinator: SourceRemovalDownloadCoordinator,
) : SourceRepository by delegate {
    override suspend fun removeSource(sourceId: SourceId): Boolean {
        val plan = removalCoordinator.capture(sourceId) ?: return false
        try {
            if (!removalCoordinator.quiesce(plan)) {
                withContext(NonCancellable) { removalCoordinator.restore(plan) }
                return false
            }

            if (!delegate.removeSource(sourceId)) {
                withContext(NonCancellable) { removalCoordinator.restore(plan) }
                return false
            }

            try {
                withContext(NonCancellable) { removalCoordinator.finalize(plan) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The source deletion has committed; cleanup failure cannot restore source ownership.
            }
            return true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (removalCoordinator.sourceExists(sourceId)) {
                    removalCoordinator.restore(plan)
                } else {
                    removalCoordinator.finalize(plan)
                }
            }
            throw cancelled
        }
    }
}
