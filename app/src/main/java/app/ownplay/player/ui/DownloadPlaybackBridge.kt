package app.ownplay.player.ui

import app.ownplay.player.download.OfflineDownload

/**
 * Narrow handoff from download-management UI to the activity-level offline playback host.
 * It does not own playback, persistence, navigation, or a player instance.
 */
internal object DownloadPlaybackBridge {
    private var owner: Any? = null
    private var action: ((OfflineDownload) -> Unit)? = null
    private var focusReturnOwner: Any? = null
    private var focusReturnAction: ((String) -> Unit)? = null

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

    fun registerFocusReturn(
        owner: Any,
        action: (downloadId: String) -> Unit,
    ) {
        focusReturnOwner = owner
        focusReturnAction = action
    }

    fun clearFocusReturn(owner: Any) {
        if (focusReturnOwner === owner) {
            focusReturnOwner = null
            focusReturnAction = null
        }
    }

    fun notifyPlaybackClosed(downloadId: String) {
        focusReturnAction?.invoke(downloadId)
    }
}
