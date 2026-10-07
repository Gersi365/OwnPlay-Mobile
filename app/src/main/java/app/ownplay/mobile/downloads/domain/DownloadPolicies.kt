package app.ownplay.mobile.downloads.domain

object DownloadStateTransitionPolicy {
    fun canTransition(
        current: DownloadStatus,
        target: DownloadStatus,
    ): Boolean {
        if (current == target) return true
        return when (current) {
            DownloadStatus.WAITING_FOR_WIFI -> target in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
            )
            DownloadStatus.QUEUED -> target in setOf(
                DownloadStatus.WAITING_FOR_WIFI,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
            )
            DownloadStatus.DOWNLOADING -> target in setOf(
                DownloadStatus.WAITING_FOR_WIFI,
                DownloadStatus.PAUSED,
                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
            )
            DownloadStatus.PAUSED -> target in setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.CANCELED,
                DownloadStatus.FAILED,
            )
            DownloadStatus.FAILED,
            DownloadStatus.MISSING,
            DownloadStatus.CANCELED,
            -> target == DownloadStatus.QUEUED
            DownloadStatus.COMPLETED,
            DownloadStatus.UNKNOWN,
            -> false
        }
    }
}

object DownloadProgressPolicy {
    fun isValid(
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): Boolean =
        bytesDownloaded >= 0L &&
            (totalBytes == null || totalBytes > 0L) &&
            (totalBytes == null || bytesDownloaded <= totalBytes)
}

object DownloadIntegrityPolicy {
    private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")

    fun normalizeSha256(value: String?): String? {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return candidate.lowercase().takeIf(SHA_256::matches)
    }
}
