package app.ownplay.player.download

/**
 * Narrow contextual handoff from a user-initiated fresh download to the activity-owned
 * runtime permission launcher. Permission state never gates repository enqueue/reliability.
 */
internal object DownloadNotificationPermissionBridge {
    private var owner: Any? = null
    private var action: (() -> Unit)? = null

    fun register(owner: Any, action: () -> Unit) {
        this.owner = owner
        this.action = action
    }

    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            action = null
        }
    }

    fun requestIfNeeded(): Boolean {
        val current = action ?: return false
        current()
        return true
    }
}
