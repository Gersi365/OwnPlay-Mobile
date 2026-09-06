package app.ownplay.player.personalization

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.player.BuildConfig
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

enum class AppInputMode {
    TOUCHSCREEN,
    DPAD,
}

enum class AppDeviceProfile(
    val inputMode: AppInputMode,
) {
    SMARTPHONE(AppInputMode.TOUCHSCREEN),
    ANDROID_TV(AppInputMode.DPAD);

    val usesDpad: Boolean
        get() = inputMode == AppInputMode.DPAD
}

data class AppDeviceSettings(
    val profile: AppDeviceProfile,
    val smartphoneOrientation: AppOrientationMode,
) {
    val effectiveOrientation: AppOrientationMode
        get() = if (profile == AppDeviceProfile.SMARTPHONE) {
            smartphoneOrientation
        } else {
            AppOrientationMode.LANDSCAPE
        }

    val inputMode: AppInputMode
        get() = profile.inputMode
}

sealed interface AppDeviceProfileSelection {
    data object Loading : AppDeviceProfileSelection
    data class Configured(
        val settings: AppDeviceSettings,
    ) : AppDeviceProfileSelection
}

class AppDeviceProfileStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.ownPlayAppPreferences

    fun observeSelection(): Flow<AppDeviceProfileSelection> = safePreferences(dataStore)
        .map { preferences ->
            AppDeviceProfileSelection.Configured(
                AppDeviceSettings(
                    profile = buildTargetDeviceProfile(),
                    smartphoneOrientation = AppOrientationMode.fromStored(
                        preferences[ORIENTATION_KEY],
                    ),
                ),
            )
        }

    suspend fun setSmartphoneOrientation(mode: AppOrientationMode): Boolean {
        if (buildTargetDeviceProfile() != AppDeviceProfile.SMARTPHONE) return false
        return editSafely { preferences ->
            preferences[ORIENTATION_KEY] = mode.storedValue
        }
    }

    private suspend fun editSafely(block: (MutablePreferences) -> Unit): Boolean = try {
        dataStore.edit { preferences -> block(preferences) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

sealed interface AppOrientationSelection {
    data object Loading : AppOrientationSelection
    data object Unconfigured : AppOrientationSelection
    data class Configured(
        val mode: AppOrientationMode,
    ) : AppOrientationSelection
}

/**
 * Compatibility facade for the compile-time Mobile/TV targets.
 *
 * Mobile may change Smartphone orientation. TV is permanently landscape and rejects orientation
 * writes. Runtime profile changes cannot switch either APK into the other target's input model.
 */
class AppOrientationStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.ownPlayAppPreferences

    fun observe(): Flow<AppOrientationMode> = safePreferences(dataStore)
        .map { preferences -> effectiveOrientation(preferences) }

    fun observeSelection(): Flow<AppOrientationSelection> = safePreferences(dataStore)
        .map { preferences ->
            if (buildTargetDeviceProfile() != AppDeviceProfile.SMARTPHONE) {
                AppOrientationSelection.Configured(AppOrientationMode.LANDSCAPE)
            } else {
                val storedMode = AppOrientationMode.fromStoredOrNull(preferences[ORIENTATION_KEY])
                if (storedMode == null) {
                    AppOrientationSelection.Unconfigured
                } else {
                    AppOrientationSelection.Configured(storedMode)
                }
            }
        }

    suspend fun set(mode: AppOrientationMode): Boolean {
        if (buildTargetDeviceProfile() != AppDeviceProfile.SMARTPHONE) return false
        return try {
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

    private fun effectiveOrientation(preferences: Preferences): AppOrientationMode =
        if (buildTargetDeviceProfile() == AppDeviceProfile.SMARTPHONE) {
            AppOrientationMode.fromStored(preferences[ORIENTATION_KEY])
        } else {
            AppOrientationMode.LANDSCAPE
        }
}

private fun buildTargetDeviceProfile(): AppDeviceProfile = if (BuildConfig.IS_TV_BUILD) {
    AppDeviceProfile.ANDROID_TV
} else {
    AppDeviceProfile.SMARTPHONE
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
