package app.ownplay.player.download

import android.content.Context

internal class DownloadNotificationPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun wasRequestAttempted(): Boolean =
        preferences.getBoolean(KEY_REQUEST_ATTEMPTED, false)

    fun markRequestAttempted() {
        preferences.edit().putBoolean(KEY_REQUEST_ATTEMPTED, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ownplay_download_notification_permission"
        const val KEY_REQUEST_ATTEMPTED = "request_attempted"
    }
}
