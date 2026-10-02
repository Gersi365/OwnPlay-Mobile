package app.ownplay.mobile.feature.settings.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.R
import app.ownplay.mobile.feature.settings.domain.SourceRefreshRetryPolicy
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class SourceRefreshNotificationController(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(applicationContext)

    init {
        ensureChannel()
    }

    fun showRepeatedFailure(sourceId: SourceId, sourceName: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("Refresh failed")
            .setContentText("$sourceName has failed to refresh repeatedly.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openSettingsPendingIntent(sourceId))
            .addAction(
                R.drawable.ic_download_notification,
                "Retry",
                retryPendingIntent(sourceId),
            )
            .addAction(
                R.drawable.ic_download_notification,
                "Open source settings",
                openSettingsPendingIntent(sourceId),
            )
            .build()
        notifySafely(sourceId, notification)
    }

    fun showAuthenticationRequired(sourceId: SourceId, sourceName: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("Authentication required")
            .setContentText("Update credentials for $sourceName.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openSettingsPendingIntent(sourceId))
            .addAction(
                R.drawable.ic_download_notification,
                "Update credentials",
                openSettingsPendingIntent(sourceId),
            )
            .build()
        notifySafely(sourceId, notification)
    }

    fun clear(sourceId: SourceId) {
        manager.cancel(notificationId(sourceId))
    }

    private fun notifySafely(sourceId: SourceId, notification: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { manager.notify(notificationId(sourceId), notification) }
    }

    private fun retryPendingIntent(sourceId: SourceId): PendingIntent {
        val intent = Intent(applicationContext, SourceRefreshNotificationActionReceiver::class.java)
            .setAction(SourceRefreshNotificationActionReceiver.ACTION_RETRY)
            .putExtra(SourceRefreshNotificationActionReceiver.EXTRA_SOURCE_ID, sourceId.value)
        return PendingIntent.getBroadcast(
            applicationContext,
            notificationId(sourceId) + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openSettingsPendingIntent(sourceId: SourceId): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(ACTION_OPEN_SOURCE_SETTINGS)
            .putExtra(EXTRA_SOURCE_ID, sourceId.value)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            applicationContext,
            notificationId(sourceId) + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager =
            applicationContext.getSystemService(NotificationManager::class.java) ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Source refresh",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "OwnPlay provider refresh failures that need attention"
            },
        )
    }

    private fun notificationId(sourceId: SourceId): Int =
        0x52000000 or (sourceId.value.hashCode() and 0x00ffffff)

    companion object {
        const val ACTION_OPEN_SOURCE_SETTINGS =
            "app.ownplay.mobile.action.OPEN_SOURCE_SETTINGS"
        const val EXTRA_SOURCE_ID = "source_id"
        private const val CHANNEL_ID = "ownplay_source_refresh"
    }
}

class SourceRefreshNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RETRY) return
        val rawId = intent.getStringExtra(EXTRA_SOURCE_ID)?.takeIf(String::isNotBlank) ?: return
        val sourceId = runCatching { SourceId(rawId) }.getOrNull() ?: return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferences = SourceRefreshSchedulePreferences(application.applicationContext)
                val notifications =
                    SourceRefreshNotificationController(application.applicationContext)
                val sourceName = application.services.sourceRepository.observeSources()
                    .first()
                    .firstOrNull { it.sourceId == sourceId }
                    ?.displayName
                    ?: "OwnPlay source"

                when (val result = application.services.sourceRepository.refreshSource(sourceId)) {
                    SourceRefreshResult.Success -> {
                        preferences.recordAutomaticSuccess(sourceId)
                        notifications.clear(sourceId)
                    }

                    is SourceRefreshResult.Failure -> {
                        val state = preferences.recordAutomaticFailure(sourceId, result.category)
                        if (result.category == SourceRefreshFailureCategory.AUTHENTICATION) {
                            WorkManagerSourceRefreshScheduler(application.applicationContext)
                                .cancel(sourceId)
                            notifications.showAuthenticationRequired(sourceId, sourceName)
                        } else if (state.consecutiveFailures >= 3) {
                            notifications.showRepeatedFailure(sourceId, sourceName)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_RETRY = "app.ownplay.mobile.action.RETRY_SOURCE_REFRESH"
        internal const val EXTRA_SOURCE_ID = "source_id"
    }
}
