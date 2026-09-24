package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class WorkManagedDownloadRepository(
    private val delegate: DownloadRepository,
    private val scheduler: DownloadWorkScheduler,
    private val storage: DownloadStorage,
    private val notifications: DownloadNotificationEvents,
    private val availabilityProbe: DownloadedMediaAvailabilityProbe,
    private val preferencesRepository: DownloadPreferencesRepository,
    private val destinationAssignments: DownloadDestinationAssignmentStore,
) : DownloadRepository {
    override fun observeDownloads(sourceId: SourceId): Flow<List<DownloadItem>> =
        delegate.observeDownloads(sourceId).map { items ->
            items.map { item -> reconcileCompletedOutput(item) }
        }

    override fun observeDownload(downloadId: DownloadId): Flow<DownloadItem?> =
        delegate.observeDownload(downloadId).map { item ->
            item?.let { reconcileCompletedOutput(it) }
        }

    override suspend fun get(downloadId: DownloadId): DownloadItem? =
        delegate.get(downloadId)?.let { reconcileCompletedOutput(it) }

    override suspend fun enqueue(request: DownloadRequest): DownloadItem {
        val item = delegate.enqueue(request)
        if (item.status == DownloadStatus.QUEUED) {
            val destination = preferencesRepository.current().destinationRelativePath
            if (!destinationAssignments.pin(item.downloadId, destination)) {
                delegate.fail(item.downloadId, DownloadFailureCode.STORAGE)
                return delegate.get(item.downloadId) ?: item
            }
            notifications.cancelResult(item.downloadId)
            scheduler.enqueue(item.downloadId, replace = false)
        }
        return item
    }

    override suspend fun markWaitingForWifi(downloadId: DownloadId): Boolean =
        delegate.markWaitingForWifi(downloadId)

    override suspend fun markQueued(downloadId: DownloadId): Boolean =
        delegate.markQueued(downloadId)

    override suspend fun markDownloading(downloadId: DownloadId): Boolean =
        delegate.markDownloading(downloadId)

    override suspend fun updateProgress(
        downloadId: DownloadId,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): Boolean = delegate.updateProgress(downloadId, bytesDownloaded, totalBytes)

    override suspend fun pause(downloadId: DownloadId): Boolean {
        val changed = delegate.pause(downloadId)
        if (changed) {
            scheduler.cancel(downloadId)
            val paused = try {
                delegate.get(downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            paused?.let { item ->
                runCatching { notifications.showPaused(item) }
            }
        }
        return changed
    }

    override suspend fun resume(downloadId: DownloadId): Boolean {
        val changed = delegate.resume(downloadId)
        if (changed) {
            notifications.cancelResult(downloadId)
            scheduler.enqueue(downloadId, replace = true)
        }
        return changed
    }

    override suspend fun cancel(downloadId: DownloadId): Boolean {
        val changed = delegate.cancel(downloadId)
        if (changed) {
            scheduler.cancel(downloadId)
            runCatching { notifications.cancelAll(downloadId) }
        }
        return changed
    }

    override suspend fun complete(
        downloadId: DownloadId,
        localReference: String,
        verifiedBytes: Long,
        sha256: String?,
    ): Boolean {
        return delegate.complete(downloadId, localReference, verifiedBytes, sha256)
    }

    override suspend fun fail(
        downloadId: DownloadId,
        failureCode: DownloadFailureCode,
    ): Boolean {
        val existing = delegate.get(downloadId)
        if (
            failureCode == DownloadFailureCode.INTEGRITY &&
            existing?.status == DownloadStatus.COMPLETED
        ) {
            existing.localReference?.let { reference ->
                runCatching { storage.removePublished(reference) }
            }
        }
        return delegate.fail(downloadId, failureCode)
    }

    override suspend fun remove(downloadId: DownloadId): Boolean {
        val existing = delegate.get(downloadId) ?: return false
        scheduler.cancel(downloadId)
        if (
            existing.status == DownloadStatus.COMPLETED &&
            existing.localReference != null &&
            !storage.removePublished(existing.localReference)
        ) {
            return false
        }
        val removed = delegate.remove(downloadId)
        if (removed) {
            notifications.cancelAll(downloadId)
            destinationAssignments.remove(downloadId)
        }
        return removed
    }

    private suspend fun reconcileCompletedOutput(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.COMPLETED) return item

        val available = try {
            availabilityProbe.isAvailable(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (available) return item

        if (!fail(item.downloadId, DownloadFailureCode.INTEGRITY)) return item
        return delegate.get(item.downloadId) ?: item
    }
}
