package app.ownplay.player.source.selection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.ownPlayActivePlaylistPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "ownplay_active_playlist_preferences",
)

sealed interface ActivePlaylistSelection {
    data object Loading : ActivePlaylistSelection

    data class Ready(
        val sourceId: String?,
    ) : ActivePlaylistSelection
}

class ActivePlaylistStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.ownPlayActivePlaylistPreferences

    fun observe(): Flow<ActivePlaylistSelection> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            ActivePlaylistSelection.Ready(preferences[ACTIVE_PLAYLIST_KEY])
        }

    suspend fun set(sourceId: String?): Boolean = try {
        dataStore.edit { preferences ->
            if (sourceId.isNullOrBlank()) {
                preferences.remove(ACTIVE_PLAYLIST_KEY)
            } else {
                preferences[ACTIVE_PLAYLIST_KEY] = sourceId
            }
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

internal fun resolveActivePlaylistId(
    persistedSourceId: String?,
    currentSourceId: String?,
    enabledSourceIds: List<String>,
): String? {
    val enabled = enabledSourceIds.toSet()
    return when {
        persistedSourceId != null && persistedSourceId in enabled -> persistedSourceId
        currentSourceId != null && currentSourceId in enabled -> currentSourceId
        enabledSourceIds.isNotEmpty() -> enabledSourceIds.first()
        else -> null
    }
}

private val ACTIVE_PLAYLIST_KEY = stringPreferencesKey("active_playlist_source_id")
