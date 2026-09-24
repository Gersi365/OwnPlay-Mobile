package app.ownplay.mobile.downloads.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class DownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val rawId = inputData.getString(KEY_DOWNLOAD_ID)?.takeIf(String::isNotBlank)
            ?: return@coroutineScope Result.failure()
        val downloadId = runCatching { DownloadId(rawId) }.getOrNull()
            ?: return@coroutineScope Result.failure()
        val application = applicationContext as? OwnPlayApplication
            ?: return@coroutineScope Result.failure()
        val services = application.services
        val preferences = services.downloadPreferencesRepository.current()
        var initial = services.downloadRepository.get(downloadId)
            ?: return@coroutineScope Result.success()
        if (initial.status !in ACTIVE_STATES) {
            return@coroutineScope Result.success()
        }

        if (preferences.wifiOnly && !applicationContext.isWifiConnectedForDownload()) {
            services.downloadRepository.markWaitingForWifi(downloadId)
            return@coroutineScope Result.retry()
        }
        if (initial.status == DownloadStatus.WAITING_FOR_WIFI) {
            if (!services.downloadRepository.markQueued(downloadId)) {
                return@coroutineScope Result.retry()
            }
            initial = services.downloadRepository.get(downloadId)
                ?: return@coroutineScope Result.success()
        }
        if (!services.downloadQueueCoordinator.tryAdmit(downloadId)) {
            return@coroutineScope Result.retry()
        }
        initial = services.downloadRepository.get(downloadId)
            ?: return@coroutineScope Result.success()

        // Long-running WorkManager execution depends on foreground promotion.
        // The user notification preference controls terminal result notifications only;
        // it must not disable the active progress/cancel foreground contract.
        services.downloadNotifications.cancelResult(downloadId)
        setForeground(services.downloadNotifications.foregroundInfo(initial))
        val foregroundUpdates = launch {
            services.downloadRepository.observeDownload(downloadId)
                .filterNotNull()
                .collect { item ->
                    if (item.status in ACTIVE_STATES) {
                        setForeground(services.downloadNotifications.foregroundInfo(item))
                    }
                }
        }

        try {
            val outcome = services.downloadExecutor.execute(downloadId)
            if (preferences.notificationsEnabled) {
                services.downloadNotifications.showTerminal(
                    services.downloadRepository.get(downloadId),
                )
            }
            when (outcome) {
                DownloadExecutionOutcome.COMPLETED,
                DownloadExecutionOutcome.SKIPPED,
                DownloadExecutionOutcome.PERSISTED_FAILURE,
                -> Result.success()
            }
        } finally {
            foregroundUpdates.cancel()
        }
    }

    companion object {
        internal const val KEY_DOWNLOAD_ID = "download_id"
        private val ACTIVE_STATES = setOf(
            DownloadStatus.WAITING_FOR_WIFI,
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
        )
    }
}


private fun Context.isWifiConnectedForDownload(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
