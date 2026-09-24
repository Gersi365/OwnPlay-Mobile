package app.ownplay.mobile.downloads.domain

enum class DownloadUserAction {
    DOWNLOAD,
    PAUSE,
    RESUME,
    RETRY,
    PLAY_OFFLINE,
    REMOVE,
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
            DownloadUserAction.PLAY_OFFLINE, DownloadUserAction.REMOVE -> false
        }
    }
}
