package app.ownplay.mobile.feature.settings.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.feature.settings.domain.SourceRefreshRetryPolicy
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

internal interface SourceRefreshScheduler {
    fun apply(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
        wifiOnly: Boolean = false,
    )

    fun cancel(sourceId: SourceId)
}

internal class WorkManagerSourceRefreshScheduler(
    context: Context,
) : SourceRefreshScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun apply(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
        wifiOnly: Boolean,
    ) {
        val repeatHours = schedule.repeatHours
        if (repeatHours == null) {
            cancel(sourceId)
            return
        }
        val request = PeriodicWorkRequestBuilder<SourceRefreshWorker>(repeatHours, TimeUnit.HOURS)
            .setInputData(workDataOf(SourceRefreshWorker.KEY_SOURCE_ID to sourceId.value))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName(sourceId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel(sourceId: SourceId) {
        workManager.cancelUniqueWork(uniqueWorkName(sourceId))
    }

    private fun uniqueWorkName(sourceId: SourceId): String =
        "ownplay-source-refresh:${sourceId.value}"
}

class SourceRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val rawId = inputData.getString(KEY_SOURCE_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val sourceId = runCatching { SourceId(rawId) }.getOrNull()
            ?: return Result.failure()
        val application = applicationContext as? OwnPlayApplication
            ?: return Result.failure()
        val preferences = SourceRefreshSchedulePreferences(applicationContext)
        val scheduler = WorkManagerSourceRefreshScheduler(applicationContext)
        val notifications = SourceRefreshNotificationController(applicationContext)

        if (preferences.currentAutomaticState(sourceId).authenticationSuspended) {
            return Result.success()
        }
        if (preferences.currentWifiOnly(sourceId) && !applicationContext.isWifiConnected()) {
            return Result.retry()
        }

        val sourceName = application.services.sourceRepository.observeSources()
            .first()
            .firstOrNull { it.sourceId == sourceId }
            ?.displayName
            ?: "OwnPlay source"

        return when (val refresh = application.services.sourceRepository.refreshSource(sourceId)) {
            SourceRefreshResult.Success -> {
                preferences.recordAutomaticSuccess(sourceId)
                notifications.clear(sourceId)
                Result.success()
            }

            is SourceRefreshResult.Failure -> {
                val state = preferences.recordAutomaticFailure(sourceId, refresh.category)
                if (refresh.category == SourceRefreshFailureCategory.AUTHENTICATION) {
                    scheduler.cancel(sourceId)
                    notifications.showAuthenticationRequired(sourceId, sourceName)
                    Result.success()
                } else {
                    if (state.consecutiveFailures >= FAILURE_NOTIFICATION_THRESHOLD) {
                        notifications.showRepeatedFailure(sourceId, sourceName)
                    }
                    if (SourceRefreshRetryPolicy.shouldRetry(refresh.category)) {
                        Result.retry()
                    } else {
                        Result.success()
                    }
                }
            }
        }
    }

    companion object {
        internal const val KEY_SOURCE_ID = "source_id"
        private const val FAILURE_NOTIFICATION_THRESHOLD = 3
    }
}

private fun Context.isWifiConnected(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
