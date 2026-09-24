package app.ownplay.mobile.downloads.domain

import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.Flow

@JvmInline
value class DownloadId(val value: String) {
    init {
        require(value.isNotBlank()) { "DownloadId must not be blank" }
    }
}

enum class DownloadMediaKind {
    MOVIE,
    EPISODE,
}

enum class DownloadStatus {
    WAITING_FOR_WIFI,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    MISSING,
    CANCELED,
    UNKNOWN,
}

enum class DownloadFailureCode {
    NETWORK,
    TIMEOUT,
    SOURCE_UNAVAILABLE,
    STORAGE,
    INTEGRITY,
    UNKNOWN,
}

data class DownloadRequest(
    val sourceId: SourceId,
    val mediaKind: DownloadMediaKind,
    val contentId: String,
    val title: String,
    val expectedBytes: Long? = null,
) {
    init {
        require(contentId.isNotBlank()) { "Download content id must not be blank" }
        require(title.isNotBlank()) { "Download title must not be blank" }
        require(expectedBytes == null || expectedBytes > 0L) {
            "Expected download size must be positive when present"
        }
    }
}

data class DownloadItem(
    val downloadId: DownloadId,
    val sourceId: SourceId,
    val mediaKind: DownloadMediaKind,
    val contentId: String,
    val title: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localReference: String?,
    val verifiedBytes: Long?,
    val sha256: String?,
    val failureCode: DownloadFailureCode?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(contentId.isNotBlank()) { "Download content id must not be blank" }
        require(title.isNotBlank()) { "Download title must not be blank" }
        require(bytesDownloaded >= 0L) { "Downloaded bytes must not be negative" }
        require(totalBytes == null || totalBytes > 0L) { "Total bytes must be positive when present" }
        require(totalBytes == null || bytesDownloaded <= totalBytes) {
            "Downloaded bytes must not exceed total bytes"
        }
        require(verifiedBytes == null || verifiedBytes > 0L) {
            "Verified bytes must be positive when present"
        }
    }
}

interface DownloadRepository {
    fun observeDownloads(sourceId: SourceId): Flow<List<DownloadItem>>

    fun observeDownload(downloadId: DownloadId): Flow<DownloadItem?>

    suspend fun get(downloadId: DownloadId): DownloadItem?

    suspend fun enqueue(request: DownloadRequest): DownloadItem

    suspend fun markWaitingForWifi(downloadId: DownloadId): Boolean

    suspend fun markQueued(downloadId: DownloadId): Boolean

    suspend fun markDownloading(downloadId: DownloadId): Boolean

    suspend fun updateProgress(
        downloadId: DownloadId,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): Boolean

    suspend fun pause(downloadId: DownloadId): Boolean

    suspend fun resume(downloadId: DownloadId): Boolean

    suspend fun cancel(downloadId: DownloadId): Boolean

    suspend fun complete(
        downloadId: DownloadId,
        localReference: String,
        verifiedBytes: Long,
        sha256: String? = null,
    ): Boolean

    suspend fun fail(
        downloadId: DownloadId,
        failureCode: DownloadFailureCode,
    ): Boolean

    suspend fun remove(downloadId: DownloadId): Boolean
}
