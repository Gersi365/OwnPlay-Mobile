package app.ownplay.mobile.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ownPlayV2Preferences: DataStore<Preferences> by preferencesDataStore(
    name = "ownplay_v2_preferences",
)

class SourceSelectionStore(context: Context) {
    private val dataStore = context.applicationContext.ownPlayV2Preferences

    fun observeActiveSourceId(): Flow<String?> =
        dataStore.data.map { preferences -> preferences[ACTIVE_SOURCE_ID] }

    suspend fun setActiveSourceId(sourceId: String?) {
        dataStore.edit { preferences ->
            if (sourceId.isNullOrBlank()) {
                preferences.remove(ACTIVE_SOURCE_ID)
            } else {
                preferences[ACTIVE_SOURCE_ID] = sourceId
            }
        }
    }

    companion object {
        private val ACTIVE_SOURCE_ID = stringPreferencesKey("active_source_id")
    }
}

fun resolveActiveSourceId(
    persistedSourceId: String?,
    enabledSourceIds: List<String>,
): String? = when {
    persistedSourceId != null && persistedSourceId in enabledSourceIds -> persistedSourceId
    enabledSourceIds.isNotEmpty() -> enabledSourceIds.first()
    else -> null
}
