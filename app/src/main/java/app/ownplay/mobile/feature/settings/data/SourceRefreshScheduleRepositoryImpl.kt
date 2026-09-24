package app.ownplay.mobile.feature.settings.data

import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.feature.settings.domain.SourceRefreshScheduleRepository
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRepository
import kotlinx.coroutines.flow.Flow

internal interface SourceRefreshScheduleCleanup {
    suspend fun clearSource(sourceId: SourceId)
}

internal class ManagedSourceRefreshScheduleRepository(
    private val store: SourceRefreshScheduleStore,
    private val scheduler: SourceRefreshScheduler,
    private val sourceExists: suspend (SourceId) -> Boolean,
) : SourceRefreshScheduleRepository, SourceRefreshScheduleCleanup {
    override fun observeSchedule(sourceId: SourceId): Flow<SourceRefreshSchedule> =
        store.observe(sourceId)

    override fun observeWifiOnly(sourceId: SourceId): Flow<Boolean> =
        store.observeWifiOnly(sourceId)

    override suspend fun setSchedule(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
    ): Boolean {
        if (!runCatching { sourceExists(sourceId) }.getOrDefault(false)) return false
        val previous = runCatching { store.current(sourceId) }.getOrNull() ?: return false
        val wifiOnly = runCatching { store.currentWifiOnly(sourceId) }.getOrDefault(false)
        return try {
            store.set(sourceId, schedule)
            store.clearAutomaticSuspension(sourceId)
            scheduler.apply(sourceId, schedule, wifiOnly)
            true
        } catch (_: Exception) {
            runCatching { store.set(sourceId, previous) }
            runCatching { scheduler.apply(sourceId, previous, wifiOnly) }
            false
        }
    }

    override suspend fun setWifiOnly(
        sourceId: SourceId,
        enabled: Boolean,
    ): Boolean {
        if (!runCatching { sourceExists(sourceId) }.getOrDefault(false)) return false
        val previous = runCatching { store.currentWifiOnly(sourceId) }.getOrDefault(false)
        val schedule = runCatching { store.current(sourceId) }.getOrNull() ?: return false
        return try {
            store.setWifiOnly(sourceId, enabled)
            scheduler.apply(sourceId, schedule, enabled)
            true
        } catch (_: Exception) {
            runCatching { store.setWifiOnly(sourceId, previous) }
            runCatching { scheduler.apply(sourceId, schedule, previous) }
            false
        }
    }

    override suspend fun clearSource(sourceId: SourceId) {
        runCatching { scheduler.cancel(sourceId) }
        runCatching { store.clear(sourceId) }
    }
}

internal class RefreshScheduleAwareSourceRepository(
    private val delegate: SourceRepository,
    private val scheduleCleanup: SourceRefreshScheduleCleanup,
    private val refreshStore: SourceRefreshScheduleStore? = null,
    private val scheduler: SourceRefreshScheduler? = null,
) : SourceRepository by delegate {
    override suspend fun reconnectSource(
        sourceId: SourceId,
        input: SourceReconnectInput,
    ): SourceMutationResult {
        val result = delegate.reconnectSource(sourceId, input)
        val store = refreshStore
        val workScheduler = scheduler
        if (result is SourceMutationResult.Success && store != null && workScheduler != null) {
            runCatching {
                store.clearAutomaticSuspension(sourceId)
                workScheduler.apply(
                    sourceId,
                    store.current(sourceId),
                    store.currentWifiOnly(sourceId),
                )
            }
        }
        return result
    }

    override suspend fun removeSource(sourceId: SourceId): Boolean {
        if (!delegate.removeSource(sourceId)) return false
        runCatching { scheduleCleanup.clearSource(sourceId) }
        return true
    }
}
