package app.ownplay.mobile.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rebuildRefreshPreferencesDataStore by preferencesDataStore(
    name = "ownplay_rebuild_refresh_preferences",
)

internal data class SourceRefreshAutomaticState(
    val consecutiveFailures: Int = 0,
    val authenticationSuspended: Boolean = false,
)

internal interface SourceRefreshScheduleStore {
    fun observe(sourceId: SourceId): Flow<SourceRefreshSchedule>
    fun observeWifiOnly(sourceId: SourceId): Flow<Boolean>
    suspend fun current(sourceId: SourceId): SourceRefreshSchedule
    suspend fun currentOrNull(sourceId: SourceId): SourceRefreshSchedule?
    suspend fun currentWifiOnly(sourceId: SourceId): Boolean
    suspend fun currentAutomaticState(sourceId: SourceId): SourceRefreshAutomaticState
    suspend fun set(sourceId: SourceId, schedule: SourceRefreshSchedule)
    suspend fun setWifiOnly(sourceId: SourceId, enabled: Boolean)
    suspend fun recordAutomaticSuccess(sourceId: SourceId)
    suspend fun recordAutomaticFailure(
        sourceId: SourceId,
        category: SourceRefreshFailureCategory,
    ): SourceRefreshAutomaticState
    suspend fun clearAutomaticSuspension(sourceId: SourceId)
    suspend fun clear(sourceId: SourceId)
}

internal class SourceRefreshSchedulePreferences(
    private val context: Context,
) : SourceRefreshScheduleStore {
    override fun observe(sourceId: SourceId): Flow<SourceRefreshSchedule> =
        context.rebuildRefreshPreferencesDataStore.data.map { preferences ->
            decode(preferences[scheduleKey(sourceId)])
        }

    override fun observeWifiOnly(sourceId: SourceId): Flow<Boolean> =
        context.rebuildRefreshPreferencesDataStore.data.map { preferences ->
            preferences[wifiKey(sourceId)] ?: false
        }

    override suspend fun current(sourceId: SourceId): SourceRefreshSchedule =
        currentOrNull(sourceId) ?: SourceRefreshSchedule.MANUAL

    override suspend fun currentOrNull(sourceId: SourceId): SourceRefreshSchedule? =
        context.rebuildRefreshPreferencesDataStore.data.first()[scheduleKey(sourceId)]
            ?.let { value -> runCatching { SourceRefreshSchedule.valueOf(value) }.getOrNull() }

    override suspend fun currentWifiOnly(sourceId: SourceId): Boolean =
        context.rebuildRefreshPreferencesDataStore.data.first()[wifiKey(sourceId)] ?: false

    override suspend fun currentAutomaticState(sourceId: SourceId): SourceRefreshAutomaticState {
        val values = context.rebuildRefreshPreferencesDataStore.data.first()
        return SourceRefreshAutomaticState(
            consecutiveFailures = values[failureCountKey(sourceId)] ?: 0,
            authenticationSuspended = values[authSuspendedKey(sourceId)] ?: false,
        )
    }

    override suspend fun set(sourceId: SourceId, schedule: SourceRefreshSchedule) {
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            preferences[scheduleKey(sourceId)] = schedule.name
        }
    }

    override suspend fun setWifiOnly(sourceId: SourceId, enabled: Boolean) {
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            preferences[wifiKey(sourceId)] = enabled
        }
    }

    override suspend fun recordAutomaticSuccess(sourceId: SourceId) {
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            preferences[failureCountKey(sourceId)] = 0
            preferences[authSuspendedKey(sourceId)] = false
        }
    }

    override suspend fun recordAutomaticFailure(
        sourceId: SourceId,
        category: SourceRefreshFailureCategory,
    ): SourceRefreshAutomaticState {
        var state = SourceRefreshAutomaticState()
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            val nextFailures = (preferences[failureCountKey(sourceId)] ?: 0) + 1
            val authSuspended = category == SourceRefreshFailureCategory.AUTHENTICATION
            preferences[failureCountKey(sourceId)] = nextFailures
            preferences[authSuspendedKey(sourceId)] = authSuspended
            state = SourceRefreshAutomaticState(nextFailures, authSuspended)
        }
        return state
    }

    override suspend fun clearAutomaticSuspension(sourceId: SourceId) {
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            preferences[failureCountKey(sourceId)] = 0
            preferences[authSuspendedKey(sourceId)] = false
        }
    }

    override suspend fun clear(sourceId: SourceId) {
        context.rebuildRefreshPreferencesDataStore.edit { preferences ->
            preferences.remove(scheduleKey(sourceId))
            preferences.remove(wifiKey(sourceId))
            preferences.remove(failureCountKey(sourceId))
            preferences.remove(authSuspendedKey(sourceId))
        }
    }

    private fun scheduleKey(sourceId: SourceId) =
        stringPreferencesKey("source_refresh_${sourceId.value}")

    private fun wifiKey(sourceId: SourceId) =
        booleanPreferencesKey("source_refresh_wifi_only_${sourceId.value}")

    private fun failureCountKey(sourceId: SourceId) =
        intPreferencesKey("source_refresh_failure_count_${sourceId.value}")

    private fun authSuspendedKey(sourceId: SourceId) =
        booleanPreferencesKey("source_refresh_auth_suspended_${sourceId.value}")

    private fun decode(value: String?): SourceRefreshSchedule =
        value?.let { runCatching { SourceRefreshSchedule.valueOf(it) }.getOrNull() }
            ?: SourceRefreshSchedule.MANUAL
}
