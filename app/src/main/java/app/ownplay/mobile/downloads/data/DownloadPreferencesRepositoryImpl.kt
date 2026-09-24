package app.ownplay.mobile.downloads.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.rebuildDownloadPreferencesDataStore by preferencesDataStore(
    name = "ownplay_rebuild_download_preferences",
)

internal interface DownloadPreferencesStore {
    val preferences: Flow<DownloadPreferences>
    suspend fun setUnmeteredNetworkOnly(enabled: Boolean)
    suspend fun setDestinationRelativePath(relativePath: String)
    suspend fun setNotificationsEnabled(enabled: Boolean)
}

internal class DownloadPreferencesDataStore(
    private val context: Context,
) : DownloadPreferencesStore {
    override val preferences: Flow<DownloadPreferences> =
        context.rebuildDownloadPreferencesDataStore.data.map { values ->
            DownloadPreferences(
                unmeteredNetworkOnly = values[UNMETERED_NETWORK_ONLY] ?: false,
                destinationRelativePath = values[DESTINATION_RELATIVE_PATH]
                    ?.let(DownloadDestinationPolicy::normalize)
                    ?: DownloadDestinationPolicy.DEFAULT_DESTINATION,
                notificationsEnabled = values[NOTIFICATIONS_ENABLED] ?: true,
            )
        }

    override suspend fun setUnmeteredNetworkOnly(enabled: Boolean) {
        context.rebuildDownloadPreferencesDataStore.edit { values ->
            values[UNMETERED_NETWORK_ONLY] = enabled
        }
    }

    override suspend fun setDestinationRelativePath(relativePath: String) {
        val normalized = DownloadDestinationPolicy.normalize(relativePath)
            ?: error("Invalid download destination")
        context.rebuildDownloadPreferencesDataStore.edit { values ->
            values[DESTINATION_RELATIVE_PATH] = normalized
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.rebuildDownloadPreferencesDataStore.edit { values ->
            values[NOTIFICATIONS_ENABLED] = enabled
        }
    }

    private companion object {
        val UNMETERED_NETWORK_ONLY = booleanPreferencesKey("unmetered_network_only")
        val DESTINATION_RELATIVE_PATH = stringPreferencesKey("destination_relative_path")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("download_notifications_enabled")
    }
}

internal interface DownloadDestinationAssignmentStore {
    suspend fun get(downloadId: DownloadId): String?

    suspend fun pin(
        downloadId: DownloadId,
        destinationRelativePath: String,
    ): Boolean

    suspend fun remove(downloadId: DownloadId): Boolean
}

internal class DataStoreDownloadDestinationAssignmentStore(
    private val context: Context,
) : DownloadDestinationAssignmentStore {
    override suspend fun get(downloadId: DownloadId): String? =
        runCatching {
            context.rebuildDownloadPreferencesDataStore.data.first()[key(downloadId)]
                ?.let(DownloadDestinationPolicy::normalize)
        }.getOrNull()

    override suspend fun pin(
        downloadId: DownloadId,
        destinationRelativePath: String,
    ): Boolean {
        val normalized = DownloadDestinationPolicy.normalize(destinationRelativePath) ?: return false
        return try {
            context.rebuildDownloadPreferencesDataStore.edit { values ->
                val preferenceKey = key(downloadId)
                if (values[preferenceKey] == null) {
                    values[preferenceKey] = normalized
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun remove(downloadId: DownloadId): Boolean =
        try {
            context.rebuildDownloadPreferencesDataStore.edit { values ->
                values.remove(key(downloadId))
            }
            true
        } catch (_: Exception) {
            false
        }

    private fun key(downloadId: DownloadId) =
        stringPreferencesKey(
            "download_destination_" +
                downloadId.value
                    .removePrefix("download:")
                    .filter(Char::isLetterOrDigit)
                    .takeLast(64)
                    .ifBlank { "item" },
        )
}

internal class DownloadNotificationPermissionPreferences(
    private val context: Context,
) {
    val prompted: Flow<Boolean> = context.rebuildDownloadPreferencesDataStore.data
        .map { values -> values[NOTIFICATION_PERMISSION_PROMPTED] ?: false }

    suspend fun markPrompted() {
        context.rebuildDownloadPreferencesDataStore.edit { values ->
            values[NOTIFICATION_PERMISSION_PROMPTED] = true
        }
    }

    private companion object {
        val NOTIFICATION_PERMISSION_PROMPTED =
            booleanPreferencesKey("notification_permission_prompted")
    }
}

internal class DataStoreDownloadPreferencesRepository(
    private val store: DownloadPreferencesStore,
) : DownloadPreferencesRepository {
    override val preferences: Flow<DownloadPreferences> =
        store.preferences.catch { emit(DownloadPreferences()) }

    override suspend fun current(): DownloadPreferences =
        runCatching { store.preferences.first() }.getOrDefault(DownloadPreferences())

    override suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean =
        write { store.setUnmeteredNetworkOnly(enabled) }

    override suspend fun setDestinationRelativePath(relativePath: String): Boolean {
        if (DownloadDestinationPolicy.normalize(relativePath) == null) return false
        return write { store.setDestinationRelativePath(relativePath) }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean): Boolean =
        write { store.setNotificationsEnabled(enabled) }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: Exception) {
            false
        }
}
