package app.ownplay.player.personalization

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

private val Context.ownPlayAppPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "ownplay_app_preferences",
)

enum class AppOrientationMode(
    val storedValue: String,
) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape");

    companion object {
        fun fromStored(value: String?): AppOrientationMode =
            fromStoredOrNull(value) ?: PORTRAIT

        fun fromStoredOrNull(value: String?): AppOrientationMode? =
            entries.firstOrNull { mode -> mode.storedValue == value }
    }
}

sealed interface AppOrientationSelection {
    data object Loading : AppOrientationSelection
    data object Unconfigured : AppOrientationSelection
    data class Configured(
        val mode: AppOrientationMode,
    ) : AppOrientationSelection
}

/** Orientation preferences for the standalone phone/tablet application. */
class AppOrientationStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.ownPlayAppPreferences

    fun observe(): Flow<AppOrientationMode> = safePreferences(dataStore)
        .map { preferences -> AppOrientationMode.fromStored(preferences[ORIENTATION_KEY]) }

    fun observeSelection(): Flow<AppOrientationSelection> = safePreferences(dataStore)
        .map { preferences ->
            val storedMode = AppOrientationMode.fromStoredOrNull(preferences[ORIENTATION_KEY])
            if (storedMode == null) {
                AppOrientationSelection.Unconfigured
            } else {
                AppOrientationSelection.Configured(storedMode)
            }
        }

    suspend fun set(mode: AppOrientationMode): Boolean = try {
        dataStore.edit { preferences ->
            preferences[ORIENTATION_KEY] = mode.storedValue
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun safePreferences(dataStore: DataStore<Preferences>): Flow<Preferences> = dataStore.data
    .catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

private val ORIENTATION_KEY = stringPreferencesKey("app_orientation")
