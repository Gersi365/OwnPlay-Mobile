package app.ownplay.player.source

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.ownPlayOnDemandCatalogRefreshPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "ownplay_on_demand_catalog_refresh_preferences",
)

internal enum class OnDemandCatalogKind(
    val storedValue: String,
) {
    VOD("vod"),
    SERIES("series"),
}

internal class OnDemandCatalogRefreshStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.ownPlayOnDemandCatalogRefreshPreferences

    suspend fun lastSuccessAtEpochMillis(
        sourceId: String,
        kind: OnDemandCatalogKind,
    ): Long? = try {
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .first()[lastSuccessKey(sourceId, kind)]
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun markSuccess(
        sourceId: String,
        kind: OnDemandCatalogKind,
        successAtEpochMillis: Long,
    ): Boolean = try {
        dataStore.edit { preferences ->
            preferences[lastSuccessKey(sourceId, kind)] = successAtEpochMillis
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun lastSuccessKey(
        sourceId: String,
        kind: OnDemandCatalogKind,
    ): Preferences.Key<Long> =
        longPreferencesKey("last_success_${kind.storedValue}_$sourceId")
}
