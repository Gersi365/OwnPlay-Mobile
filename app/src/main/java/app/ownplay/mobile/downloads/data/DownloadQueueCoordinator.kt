package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DownloadQueueCoordinator(
    private val dao: DownloadDao,
    private val repository: DownloadRepository,
) {
    private val admissionMutex = Mutex()

    suspend fun tryAdmit(downloadId: DownloadId): Boolean = admissionMutex.withLock {
        val current = repository.get(downloadId) ?: return@withLock false
        if (current.status == DownloadStatus.DOWNLOADING) return@withLock true
        if (current.status != DownloadStatus.QUEUED) return@withLock false

        val queue = dao.getExecutionQueue()
        val runningCount = queue.count { it.state == DownloadStatus.DOWNLOADING.name }
        val availableSlots = MAX_CONCURRENT_DOWNLOADS - runningCount
        if (availableSlots <= 0) return@withLock false

        val eligible = queue
            .asSequence()
            .filter { it.state != DownloadStatus.DOWNLOADING.name }
            .take(availableSlots)
            .map { it.downloadId }
            .toSet()
        if (downloadId.value !in eligible) return@withLock false

        repository.markDownloading(downloadId)
    }

    companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 3
    }
}
