package app.ownplay.player.ui

import app.ownplay.player.download.OfflineDownload

/**
 * Narrow handoff from download-management UI to the activity-level offline playback host.
 * It does not own playback, persistence, navigation, or a player instance.
 */
internal object DownloadPlaybackBridge {
    private var owner: Any? = null
    private var action: ((OfflineDownload) -> Unit)? = null

    fun register(
        owner: Any,
        action: (OfflineDownload) -> Unit,
    ) {
        this.owner = owner
        this.action = action
    }

    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            action = null
        }
    }

    fun request(download: OfflineDownload): Boolean {
        val current = action ?: return false
        current(download)
        return true
    }
}
