package app.ownplay.mobile.downloads.domain

enum class DownloadUserAction {
    DOWNLOAD,
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
    PLAY_OFFLINE,
    FORGET,
    REMOVE,
}

object DownloadRemovalPolicy {
    fun canDeleteLocalFile(item: DownloadItem): Boolean =
        item.origin == DownloadOrigin.APP_MANAGED &&
            !item.localReference.isNullOrBlank() &&
            item.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.MISSING)
}

object DownloadUserActionPolicy {
    fun primary(status: DownloadStatus?): DownloadUserAction? = when (status) {
        null -> DownloadUserAction.DOWNLOAD
        DownloadStatus.WAITING_FOR_WIFI,
        DownloadStatus.QUEUED,
        DownloadStatus.DOWNLOADING,
        -> DownloadUserAction.PAUSE
        DownloadStatus.PAUSED -> DownloadUserAction.RESUME
        DownloadStatus.FAILED,
        DownloadStatus.MISSING,
        DownloadStatus.CANCELED,
        -> DownloadUserAction.RETRY
        DownloadStatus.UNKNOWN -> null
        DownloadStatus.COMPLETED -> DownloadUserAction.PLAY_OFFLINE
    }

    fun canRemove(status: DownloadStatus?): Boolean = status in setOf(
        DownloadStatus.PAUSED,
        DownloadStatus.FAILED,
        DownloadStatus.MISSING,
        DownloadStatus.CANCELED,
        DownloadStatus.UNKNOWN,
        DownloadStatus.COMPLETED,
    )
}

/** Re-checks the current record so a stale Retry/Pause button cannot restart a paused item. */
class DownloadActionHandler(private val repository: DownloadRepository) {
    suspend fun execute(
        action: DownloadUserAction,
        request: DownloadRequest,
        downloadId: DownloadId?,
    ): Boolean {
        if (action == DownloadUserAction.PLAY_OFFLINE) return false
        val current = downloadId?.let { repository.get(it) }
        if (downloadId != null && current == null) return false
        if (current != null && (
                current.sourceId != request.sourceId || current.mediaKind != request.mediaKind ||
                    current.contentId != request.contentId
                )
        ) return false
        if (action == DownloadUserAction.CANCEL) {
            return current != null && DownloadNotificationPolicy.canCancel(current.status) &&
                repository.cancel(current.downloadId)
        }
        if (action == DownloadUserAction.FORGET) {
            return current != null && DownloadUserActionPolicy.canRemove(current.status) &&
                repository.forget(current.downloadId)
        }
        if (action == DownloadUserAction.REMOVE) {
            return current != null && DownloadUserActionPolicy.canRemove(current.status) &&
                repository.remove(current.downloadId)
        }
        if (DownloadUserActionPolicy.primary(current?.status) != action) return false
        return when (action) {
            DownloadUserAction.DOWNLOAD, DownloadUserAction.RETRY ->
                repository.enqueue(request).status == DownloadStatus.QUEUED
            DownloadUserAction.PAUSE -> current != null && repository.pause(current.downloadId)
            DownloadUserAction.RESUME -> current != null && repository.resume(current.downloadId)
            DownloadUserAction.CANCEL,
            DownloadUserAction.FORGET,
            DownloadUserAction.PLAY_OFFLINE,
            DownloadUserAction.REMOVE,
            -> false
        }
    }
}
