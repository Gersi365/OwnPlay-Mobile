package app.ownplay.mobile.feature.settings.domain

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import kotlinx.coroutines.flow.Flow

enum class SourceRefreshSchedule(
    val displayName: String,
    val repeatHours: Long?,
) {
    MANUAL("Manual only", null),
    EVERY_6_HOURS("Every 6 hours", 6L),
    EVERY_12_HOURS("Every 12 hours", 12L),
    DAILY("Every 24 hours", 24L),
    EVERY_48_HOURS("Every 48 hours", 48L),
}

interface SourceRefreshScheduleRepository {
    fun observeSchedule(sourceId: SourceId): Flow<SourceRefreshSchedule>

    fun observeWifiOnly(sourceId: SourceId): Flow<Boolean>

    suspend fun setSchedule(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
    ): Boolean

    suspend fun setWifiOnly(
        sourceId: SourceId,
        enabled: Boolean,
    ): Boolean
}

object SourceRefreshRetryPolicy {
    fun shouldRetry(category: SourceRefreshFailureCategory): Boolean = when (category) {
        SourceRefreshFailureCategory.NETWORK,
        SourceRefreshFailureCategory.TIMEOUT,
        SourceRefreshFailureCategory.TRANSIENT_PROVIDER,
        -> true
        SourceRefreshFailureCategory.AUTHENTICATION,
        SourceRefreshFailureCategory.INVALID_PAYLOAD,
        SourceRefreshFailureCategory.PROVIDER,
        SourceRefreshFailureCategory.STORAGE,
        SourceRefreshFailureCategory.UNKNOWN,
        -> false
    }
}
