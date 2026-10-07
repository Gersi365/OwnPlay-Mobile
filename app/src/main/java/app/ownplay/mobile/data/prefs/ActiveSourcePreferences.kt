package app.ownplay.mobile.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rebuildSourcePreferencesDataStore by preferencesDataStore(
    name = "ownplay_rebuild_source_preferences",
)

interface ActiveSourceSelectionStore {
    val selectedSourceId: Flow<String?>

    suspend fun currentSelectedSourceId(): String?

    suspend fun setSelectedSourceId(sourceId: String?)
}

class ActiveSourcePreferences(
    private val context: Context,
) : ActiveSourceSelectionStore {
    override val selectedSourceId: Flow<String?> = context.rebuildSourcePreferencesDataStore.data
        .map { preferences -> preferences[ACTIVE_SOURCE_ID] }

    override suspend fun currentSelectedSourceId(): String? =
        context.rebuildSourcePreferencesDataStore.data.first()[ACTIVE_SOURCE_ID]

    override suspend fun setSelectedSourceId(sourceId: String?) {
        context.rebuildSourcePreferencesDataStore.edit { preferences ->
            if (sourceId == null) {
                preferences.remove(ACTIVE_SOURCE_ID)
            } else {
                preferences[ACTIVE_SOURCE_ID] = sourceId
            }
        }
    }

    private companion object {
        val ACTIVE_SOURCE_ID = stringPreferencesKey("active_source_id")
    }
}
