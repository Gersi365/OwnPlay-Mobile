package app.ownplay.mobile.core.playback

enum class PlaybackStartMode {
    RESUME,
    FROM_BEGINNING,
}

object PlaybackStartPolicy {
    fun startPositionMillis(
        mode: PlaybackStartMode,
        savedPositionMillis: Long,
    ): Long = when (mode) {
        PlaybackStartMode.RESUME -> savedPositionMillis.coerceAtLeast(0L)
        PlaybackStartMode.FROM_BEGINNING -> 0L
    }
}

enum class ManagedDownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    FAILED,
    COMPLETED,
}

enum class DownloadPrimaryAction {
    DOWNLOAD,
    PAUSE,
    RESUME,
    RETRY,
    PLAY_OFFLINE,
}

object DownloadActionPolicy {
    fun primaryAction(state: ManagedDownloadState?): DownloadPrimaryAction = when (state) {
        null -> DownloadPrimaryAction.DOWNLOAD
        ManagedDownloadState.QUEUED,
        ManagedDownloadState.DOWNLOADING -> DownloadPrimaryAction.PAUSE
        ManagedDownloadState.PAUSED -> DownloadPrimaryAction.RESUME
        ManagedDownloadState.FAILED -> DownloadPrimaryAction.RETRY
        ManagedDownloadState.COMPLETED -> DownloadPrimaryAction.PLAY_OFFLINE
    }
}
