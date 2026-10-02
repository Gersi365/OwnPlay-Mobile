package app.ownplay.mobile.feature.settings.data

import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRefreshScheduleRepositoryTest {
    private val sourceId = SourceId("source-a")

    @Test
    fun scheduleAndWifiPreferencePersistAndReschedule() = runBlocking {
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val repository = ManagedSourceRefreshScheduleRepository(store, scheduler) { true }

        assertTrue(repository.setSchedule(sourceId, SourceRefreshSchedule.EVERY_48_HOURS))
        assertTrue(repository.setWifiOnly(sourceId, true))

        assertEquals(SourceRefreshSchedule.EVERY_48_HOURS, store.current(sourceId))
        assertTrue(store.currentWifiOnly(sourceId))
        assertEquals(
            Triple(sourceId, SourceRefreshSchedule.EVERY_48_HOURS, true),
            scheduler.applied.last(),
        )
    }

    @Test
    fun missingSourceRejectsChanges() = runBlocking {
        val store = FakeStore()
        val scheduler = FakeScheduler()
        val repository = ManagedSourceRefreshScheduleRepository(store, scheduler) { false }

        assertFalse(repository.setSchedule(sourceId, SourceRefreshSchedule.DAILY))
        assertFalse(repository.setWifiOnly(sourceId, true))
        assertTrue(scheduler.applied.isEmpty())
    }

    @Test
    fun schedulerFailureRollsBackPersistedSchedule() = runBlocking {
        val store = FakeStore(SourceRefreshSchedule.EVERY_12_HOURS)
        val scheduler = FakeScheduler(failFirstApply = true)
        val repository = ManagedSourceRefreshScheduleRepository(store, scheduler) { true }

        assertFalse(repository.setSchedule(sourceId, SourceRefreshSchedule.DAILY))
        assertEquals(SourceRefreshSchedule.EVERY_12_HOURS, store.current(sourceId))
    }

    @Test
    fun automaticFailureStateResetsAfterSuccess() = runBlocking {
        val store = FakeStore()
        store.recordAutomaticFailure(sourceId, SourceRefreshFailureCategory.NETWORK)
        store.recordAutomaticFailure(sourceId, SourceRefreshFailureCategory.NETWORK)
        assertEquals(2, store.currentAutomaticState(sourceId).consecutiveFailures)

        store.recordAutomaticSuccess(sourceId)
        assertEquals(SourceRefreshAutomaticState(), store.currentAutomaticState(sourceId))
    }

    @Test
    fun clearSourceCancelsWorkAndClearsPreference() = runBlocking {
        val store = FakeStore(SourceRefreshSchedule.DAILY)
        val scheduler = FakeScheduler()
        val repository = ManagedSourceRefreshScheduleRepository(store, scheduler) { true }

        repository.clearSource(sourceId)

        assertEquals(listOf(sourceId), scheduler.cancelled)
        assertEquals(SourceRefreshSchedule.MANUAL, store.current(sourceId))
    }
}

private class FakeStore(
    initial: SourceRefreshSchedule = SourceRefreshSchedule.MANUAL,
) : SourceRefreshScheduleStore {
    private val schedules = mutableMapOf<String, MutableStateFlow<SourceRefreshSchedule>>()
    private val wifi = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val automatic = mutableMapOf<String, SourceRefreshAutomaticState>()
    private val initialSchedule = initial

    private fun scheduleState(sourceId: SourceId): MutableStateFlow<SourceRefreshSchedule> =
        schedules.getOrPut(sourceId.value) { MutableStateFlow(initialSchedule) }

    private fun wifiState(sourceId: SourceId): MutableStateFlow<Boolean> =
        wifi.getOrPut(sourceId.value) { MutableStateFlow(false) }

    override fun observe(sourceId: SourceId): Flow<SourceRefreshSchedule> = scheduleState(sourceId)
    override fun observeWifiOnly(sourceId: SourceId): Flow<Boolean> = wifiState(sourceId)
    override suspend fun current(sourceId: SourceId): SourceRefreshSchedule = scheduleState(sourceId).value
    override suspend fun currentOrNull(sourceId: SourceId): SourceRefreshSchedule? = scheduleState(sourceId).value
    override suspend fun currentWifiOnly(sourceId: SourceId): Boolean = wifiState(sourceId).value
    override suspend fun currentAutomaticState(sourceId: SourceId): SourceRefreshAutomaticState =
        automatic[sourceId.value] ?: SourceRefreshAutomaticState()

    override suspend fun set(sourceId: SourceId, schedule: SourceRefreshSchedule) {
        scheduleState(sourceId).value = schedule
    }

    override suspend fun setWifiOnly(sourceId: SourceId, enabled: Boolean) {
        wifiState(sourceId).value = enabled
    }

    override suspend fun recordAutomaticSuccess(sourceId: SourceId) {
        automatic[sourceId.value] = SourceRefreshAutomaticState()
    }

    override suspend fun recordAutomaticFailure(
        sourceId: SourceId,
        category: SourceRefreshFailureCategory,
    ): SourceRefreshAutomaticState {
        val prior = automatic[sourceId.value] ?: SourceRefreshAutomaticState()
        val next = SourceRefreshAutomaticState(
            consecutiveFailures = prior.consecutiveFailures + 1,
            authenticationSuspended = category == SourceRefreshFailureCategory.AUTHENTICATION,
        )
        automatic[sourceId.value] = next
        return next
    }

    override suspend fun clearAutomaticSuspension(sourceId: SourceId) {
        automatic[sourceId.value] = SourceRefreshAutomaticState()
    }

    override suspend fun clear(sourceId: SourceId) {
        scheduleState(sourceId).value = SourceRefreshSchedule.MANUAL
        wifiState(sourceId).value = false
        automatic.remove(sourceId.value)
    }
}

private class FakeScheduler(
    private var failFirstApply: Boolean = false,
) : SourceRefreshScheduler {
    val applied = mutableListOf<Triple<SourceId, SourceRefreshSchedule, Boolean>>()
    val cancelled = mutableListOf<SourceId>()

    override fun apply(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
        wifiOnly: Boolean,
    ) {
        if (failFirstApply) {
            failFirstApply = false
            error("scheduler failed")
        }
        applied += Triple(sourceId, schedule, wifiOnly)
    }

    override fun cancel(sourceId: SourceId) {
        cancelled += sourceId
    }
}
