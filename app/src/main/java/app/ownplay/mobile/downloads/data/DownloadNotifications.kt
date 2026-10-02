package app.ownplay.mobile.downloads.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.R
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadNotificationPolicy
import app.ownplay.mobile.downloads.domain.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal interface DownloadNotificationEvents {
    fun showPaused(item: DownloadItem) = Unit
    fun cancelResult(downloadId: DownloadId)
    fun cancelAll(downloadId: DownloadId)
}

internal class DownloadNotificationController(
    context: Context,
) : DownloadNotificationEvents {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)

    init {
        ensureChannel()
    }

    fun foregroundInfo(item: DownloadItem): ForegroundInfo {
        val notification = activeNotification(item)
        val id = DownloadNotificationPolicy.foregroundNotificationId(item.downloadId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    fun showTerminal(item: DownloadItem?) {
        val current = item ?: return
        val notification = when (current.status) {
            DownloadStatus.COMPLETED -> terminalNotification(
                item = current,
                message = "Download complete",
            )
            DownloadStatus.FAILED -> terminalNotification(
                item = current,
                message = "Download failed. Retry from Library.",
            )
            else -> return
        }
        notifyResult(current.downloadId, notification)
    }

    override fun showPaused(item: DownloadItem) {
        if (item.status != DownloadStatus.PAUSED) return
        notifyResult(
            item.downloadId,
            pausedNotification(item),
        )
    }

    override fun cancelResult(downloadId: DownloadId) {
        notificationManager.cancel(DownloadNotificationPolicy.resultNotificationId(downloadId))
    }

    override fun cancelAll(downloadId: DownloadId) {
        notificationManager.cancel(DownloadNotificationPolicy.foregroundNotificationId(downloadId))
        cancelResult(downloadId)
    }

    private fun activeNotification(item: DownloadItem): Notification {
        val progress = DownloadNotificationPolicy.progressPercent(
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
        )
        val text = when (item.status) {
            DownloadStatus.QUEUED -> "Waiting to download"
            DownloadStatus.DOWNLOADING -> progress?.let { "$it% downloaded" } ?: "Downloading"
            else -> "Download in progress"
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(item.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(
                R.drawable.ic_download_notification,
                "Pause",
                actionPendingIntent(item.downloadId, DownloadNotificationActionReceiver.ACTION_PAUSE),
            )
            .addAction(
                R.drawable.ic_download_notification,
                "Cancel",
                actionPendingIntent(item.downloadId, DownloadNotificationActionReceiver.ACTION_CANCEL),
            )
            .build()
    }

    private fun pausedNotification(item: DownloadItem): Notification {
        val progress = DownloadNotificationPolicy.progressPercent(
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
        )
        val text = progress?.let { "Paused · $it%" } ?: "Paused"
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(item.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(detailsPendingIntent(item))
            .addAction(
                R.drawable.ic_download_notification,
                "Resume",
                actionPendingIntent(item.downloadId, DownloadNotificationActionReceiver.ACTION_RESUME),
            )
            .addAction(
                R.drawable.ic_download_notification,
                "Cancel",
                actionPendingIntent(item.downloadId, DownloadNotificationActionReceiver.ACTION_CANCEL),
            )
            .build()
    }

    private fun terminalNotification(
        item: DownloadItem,
        message: String,
    ): Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle(item.title)
        .setContentText(message)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(detailsPendingIntent(item))
        .setAutoCancel(true)
        .build()

    private fun detailsPendingIntent(item: DownloadItem): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(DownloadNotificationNavigationContract.ACTION_OPEN_DETAILS)
            .putExtra(DownloadNotificationNavigationContract.EXTRA_DOWNLOAD_ID, item.downloadId.value)
            .putExtra(DownloadNotificationNavigationContract.EXTRA_SOURCE_ID, item.sourceId.value)
            .putExtra(DownloadNotificationNavigationContract.EXTRA_MEDIA_KIND, item.mediaKind.name)
            .putExtra(DownloadNotificationNavigationContract.EXTRA_CONTENT_ID, item.contentId)
        return PendingIntent.getActivity(
            applicationContext,
            DownloadNotificationPolicy.resultNotificationId(item.downloadId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(
        downloadId: DownloadId,
        action: String,
    ): PendingIntent {
        val intent = Intent(applicationContext, DownloadNotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(DownloadNotificationActionReceiver.EXTRA_DOWNLOAD_ID, downloadId.value)
        return PendingIntent.getBroadcast(
            applicationContext,
            DownloadNotificationPolicy.foregroundNotificationId(downloadId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyResult(
        downloadId: DownloadId,
        notification: Notification,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(
                DownloadNotificationPolicy.resultNotificationId(downloadId),
                notification,
            )
        } catch (_: SecurityException) {
            // Notification permission may be revoked between the explicit check and notify().
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "OwnPlay download progress and results"
            },
        )
    }

    private companion object {
        const val CHANNEL_ID = "ownplay_downloads"
    }
}

internal object DownloadNotificationNavigationContract {
    const val ACTION_OPEN_DETAILS = "app.ownplay.mobile.action.OPEN_DOWNLOAD_DETAILS"
    const val EXTRA_DOWNLOAD_ID = "download_id"
    const val EXTRA_SOURCE_ID = "source_id"
    const val EXTRA_MEDIA_KIND = "media_kind"
    const val EXTRA_CONTENT_ID = "content_id"
}

class DownloadNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_CANCEL) return
        val rawId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)?.takeIf(String::isNotBlank) ?: return
        val downloadId = runCatching { DownloadId(rawId) }.getOrNull() ?: return
        val application = context.applicationContext as? OwnPlayApplication ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = application.services.downloadRepository
                val current = repository.get(downloadId) ?: return@launch
                when (action) {
                    ACTION_PAUSE -> {
                        if (DownloadNotificationPolicy.canPause(current.status)) {
                            repository.pause(downloadId)
                        }
                    }
                    ACTION_RESUME -> {
                        if (DownloadNotificationPolicy.canResume(current.status)) {
                            repository.resume(downloadId)
                        }
                    }
                    ACTION_CANCEL -> {
                        if (DownloadNotificationPolicy.canCancel(current.status)) {
                            repository.cancel(downloadId)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_PAUSE = "app.ownplay.mobile.action.PAUSE_DOWNLOAD"
        internal const val ACTION_RESUME = "app.ownplay.mobile.action.RESUME_DOWNLOAD"
        internal const val ACTION_CANCEL = "app.ownplay.mobile.action.CANCEL_DOWNLOAD"
        internal const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
