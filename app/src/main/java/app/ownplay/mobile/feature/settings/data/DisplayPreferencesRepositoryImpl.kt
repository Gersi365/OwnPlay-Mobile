package app.ownplay.mobile.feature.settings.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rebuildDisplayPreferencesDataStore by preferencesDataStore(
    name = "ownplay_rebuild_display_preferences",
)

internal interface DisplayPreferencesStore {
    val preferences: Flow<DisplayPreferences>
    suspend fun setCompactMediaRows(enabled: Boolean)
    suspend fun setShowChannelLogos(enabled: Boolean)
    suspend fun setPreferTvgName(enabled: Boolean)
    suspend fun setHideChannelPrefix(enabled: Boolean)
    suspend fun setHideCategoryPrefix(enabled: Boolean)
}

internal class DisplayPreferencesDataStore(
    private val context: Context,
) : DisplayPreferencesStore {
    override val preferences: Flow<DisplayPreferences> =
        context.rebuildDisplayPreferencesDataStore.data.map { values ->
            DisplayPreferences(
                compactMediaRows = values[COMPACT_MEDIA_ROWS] ?: false,
                showChannelLogos = values[SHOW_CHANNEL_LOGOS] ?: true,
                preferTvgName = values[PREFER_TVG_NAME] ?: false,
                hideChannelPrefix = values[HIDE_CHANNEL_PREFIX] ?: true,
                hideCategoryPrefix = values[HIDE_CATEGORY_PREFIX] ?: true,
            )
        }

    override suspend fun setCompactMediaRows(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[COMPACT_MEDIA_ROWS] = enabled
        }
    }

    override suspend fun setShowChannelLogos(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[SHOW_CHANNEL_LOGOS] = enabled
        }
    }

    override suspend fun setPreferTvgName(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[PREFER_TVG_NAME] = enabled
        }
    }

    override suspend fun setHideChannelPrefix(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[HIDE_CHANNEL_PREFIX] = enabled
        }
    }

    override suspend fun setHideCategoryPrefix(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[HIDE_CATEGORY_PREFIX] = enabled
        }
    }

    private companion object {
        val COMPACT_MEDIA_ROWS = booleanPreferencesKey("compact_media_rows")
        val SHOW_CHANNEL_LOGOS = booleanPreferencesKey("show_channel_logos")
        val PREFER_TVG_NAME = booleanPreferencesKey("prefer_tvg_name")
        val HIDE_CHANNEL_PREFIX = booleanPreferencesKey("hide_channel_prefix")
        val HIDE_CATEGORY_PREFIX = booleanPreferencesKey("hide_category_prefix")
    }
}

internal class DataStoreDisplayPreferencesRepository(
    private val store: DisplayPreferencesStore,
) : DisplayPreferencesRepository {
    override val preferences: Flow<DisplayPreferences> = store.preferences

    override suspend fun setCompactMediaRows(enabled: Boolean): Boolean = write {
        store.setCompactMediaRows(enabled)
    }

    override suspend fun setShowChannelLogos(enabled: Boolean): Boolean = write {
        store.setShowChannelLogos(enabled)
    }

    override suspend fun setPreferTvgName(enabled: Boolean): Boolean = write {
        store.setPreferTvgName(enabled)
    }

    override suspend fun setHideChannelPrefix(enabled: Boolean): Boolean = write {
        store.setHideChannelPrefix(enabled)
    }

    override suspend fun setHideCategoryPrefix(enabled: Boolean): Boolean = write {
        store.setHideCategoryPrefix(enabled)
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: Exception) {
            false
        }
}
