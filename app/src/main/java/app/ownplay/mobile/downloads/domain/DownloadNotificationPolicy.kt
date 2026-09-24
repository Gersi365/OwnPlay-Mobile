package app.ownplay.mobile.downloads.domain

object DownloadNotificationPolicy {
    fun foregroundNotificationId(downloadId: DownloadId): Int = stableBaseId(downloadId)

    fun resultNotificationId(downloadId: DownloadId): Int =
        stableBaseId(downloadId) or RESULT_ID_MASK

    fun progressPercent(
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): Int? {
        if (bytesDownloaded < 0L || totalBytes == null || totalBytes <= 0L) return null
        val bounded = bytesDownloaded.coerceAtMost(totalBytes)
        return ((bounded.toDouble() / totalBytes.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    fun canPause(status: DownloadStatus): Boolean =
        status in setOf(
            DownloadStatus.WAITING_FOR_WIFI,
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
        )

    fun canResume(status: DownloadStatus): Boolean =
        status == DownloadStatus.PAUSED

    fun canCancel(status: DownloadStatus): Boolean =
        canPause(status) || canResume(status)

    private fun stableBaseId(downloadId: DownloadId): Int {
        val candidate = downloadId.value.hashCode() and BASE_ID_MASK
        return candidate.takeIf { it != 0 } ?: 1
    }

    private const val BASE_ID_MASK = 0x1fffffff
    private const val RESULT_ID_MASK = 0x20000000
}


object DownloadNotificationPermissionPromptPolicy {
    private const val ANDROID_13_API = 33

    fun shouldRequest(
        sdkInt: Int,
        notificationsGranted: Boolean,
        alreadyPrompted: Boolean,
    ): Boolean =
        sdkInt >= ANDROID_13_API && !notificationsGranted && !alreadyPrompted
}
