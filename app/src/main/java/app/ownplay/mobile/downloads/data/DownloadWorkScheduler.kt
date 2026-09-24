package app.ownplay.mobile.downloads.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.ownplay.mobile.downloads.domain.DownloadId
import java.util.concurrent.TimeUnit

internal interface DownloadWorkScheduler {
    suspend fun enqueue(downloadId: DownloadId, replace: Boolean)
    fun cancel(downloadId: DownloadId)
}

internal class WorkManagerDownloadScheduler(
    context: Context,
) : DownloadWorkScheduler {
    private val applicationContext = context.applicationContext

    override suspend fun enqueue(downloadId: DownloadId, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_DOWNLOAD_ID, downloadId.value)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            uniqueWorkName(downloadId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel(downloadId: DownloadId) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(uniqueWorkName(downloadId))
    }

    companion object {
        internal fun uniqueWorkName(downloadId: DownloadId): String =
            "ownplay-download:${downloadId.value}"
    }
}
