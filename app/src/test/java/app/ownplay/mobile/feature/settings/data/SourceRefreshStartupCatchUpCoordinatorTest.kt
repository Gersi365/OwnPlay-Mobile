package app.ownplay.mobile.feature.settings.data

import app.ownplay.mobile.data.db.RefreshStateEntity
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.data.FakeRefreshStateDao
import app.ownplay.mobile.sources.data.FakeSourceDao
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceRefreshStartupCatchUpCoordinatorTest {

    @Test
    fun startupQueuesOnlyDueOrInterruptedSourcesAndCarriesWifiPreference() = runBlocking {
        val sources = FakeSourceDao(
            listOf(
                source("due", enabled = true),
                source("not-due", enabled = true),
                source("auth-suspended", enabled = true),
                source("interrupted", enabled = true),
                source("disabled", enabled = false),
            ),
        )
        val refreshStates = FakeRefreshStateDao(
            listOf(
                state("due", "SUCCESS", NOW_MS - SIX_HOURS_MS),
                state("not-due", "SUCCESS", NOW_MS - 1L * 60 * 60 * 1_000L),
                state("auth-suspended", "FAILED", NOW_MS - SIX_HOURS_MS),
                state("interrupted", "INITIALIZING", null),
                state("disabled", "SUCCESS", NOW_MS - SIX_HOURS_MS),
            ),
        )
        val preferences = FakeScheduleStore(
            schedules = mapOf(
                "due" to SourceRefreshSchedule.EVERY_6_HOURS,
                "not-due" to SourceRefreshSchedule.EVERY_6_HOURS,
                "auth-suspended" to SourceRefreshSchedule.EVERY_6_HOURS,
                "disabled" to SourceRefreshSchedule.EVERY_6_HOURS,
            ),
            wifiOnly = setOf("due"),
            authenticationSuspended = setOf("auth-suspended"),
        )
        val scheduler = RecordingRefreshScheduler()

        SourceRefreshStartupCatchUpCoordinator(
            sourceDao = sources,
            refreshStateDao = refreshStates,
            preferences = preferences,
            scheduler = scheduler,
            nowMillis = { NOW_MS },
        ).enqueueDueSources()

        assertEquals(
            listOf("due" to true, "interrupted" to false),
            scheduler.catchUps,
        )
    }

    private fun source(id: String, enabled: Boolean) = SourceEntity(
        sourceId = id,
        displayName = id,
        type = "XTREAM",
        baseLocator = "https://$id.provider.invalid",
        credentialReference = id,
        enabled = enabled,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun state(id: String, state: String, lastSuccess: Long?) = RefreshStateEntity(
        sourceId = id,
        generation = 1L,
        state = state,
        lastAttempt = NOW_MS,
        lastSuccess = lastSuccess,
        errorCode = null,
    )

    private class FakeScheduleStore(
        private val schedules: Map<String, SourceRefreshSchedule>,
        private val wifiOnly: Set<String>,
        private val authenticationSuspended: Set<String>,
    ) : SourceRefreshScheduleStore {
        override fun observe(sourceId: SourceId): Flow<SourceRefreshSchedule> =
            flowOf(schedules[sourceId.value] ?: SourceRefreshSchedule.MANUAL)

        override fun observeWifiOnly(sourceId: SourceId): Flow<Boolean> =
            flowOf(sourceId.value in wifiOnly)

        override suspend fun current(sourceId: SourceId): SourceRefreshSchedule =
            schedules[sourceId.value] ?: SourceRefreshSchedule.MANUAL

        override suspend fun currentOrNull(sourceId: SourceId): SourceRefreshSchedule? =
            schedules[sourceId.value]

        override suspend fun currentWifiOnly(sourceId: SourceId): Boolean = sourceId.value in wifiOnly

        override suspend fun currentAutomaticState(sourceId: SourceId) = SourceRefreshAutomaticState(
            authenticationSuspended = sourceId.value in authenticationSuspended,
        )

        override suspend fun set(sourceId: SourceId, schedule: SourceRefreshSchedule) = Unit
        override suspend fun setWifiOnly(sourceId: SourceId, enabled: Boolean) = Unit
        override suspend fun recordAutomaticSuccess(sourceId: SourceId) = Unit
        override suspend fun recordAutomaticFailure(
            sourceId: SourceId,
            category: SourceRefreshFailureCategory,
        ) = SourceRefreshAutomaticState()
        override suspend fun clearAutomaticSuspension(sourceId: SourceId) = Unit
        override suspend fun clear(sourceId: SourceId) = Unit
    }

    private class RecordingRefreshScheduler : SourceRefreshScheduler {
        val catchUps = mutableListOf<Pair<String, Boolean>>()

        override fun apply(sourceId: SourceId, schedule: SourceRefreshSchedule, wifiOnly: Boolean) = Unit
        override fun cancel(sourceId: SourceId) = Unit
        override fun enqueueCatchUp(sourceId: SourceId, wifiOnly: Boolean) {
            catchUps += sourceId.value to wifiOnly
        }
    }

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
        const val SIX_HOURS_MS = 6L * 60 * 60 * 1_000L
    }
}
