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
    suspend fun setShowCategoryFlags(enabled: Boolean)
    suspend fun setHideLiveCategoryPrefix(enabled: Boolean)
    suspend fun setHideLibraryCategoryPrefix(enabled: Boolean)
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
                showCategoryFlags = values[SHOW_CATEGORY_FLAGS] ?: true,
                hideLiveCategoryPrefix = values[HIDE_LIVE_CATEGORY_PREFIX]
                    ?: values[LEGACY_HIDE_CATEGORY_PREFIX]
                    ?: false,
                hideLibraryCategoryPrefix = values[HIDE_LIBRARY_CATEGORY_PREFIX]
                    ?: values[LEGACY_HIDE_CATEGORY_PREFIX]
                    ?: false,
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

    override suspend fun setShowCategoryFlags(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[SHOW_CATEGORY_FLAGS] = enabled
        }
    }

    override suspend fun setHideLiveCategoryPrefix(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[HIDE_LIVE_CATEGORY_PREFIX] = enabled
        }
    }

    override suspend fun setHideLibraryCategoryPrefix(enabled: Boolean) {
        context.rebuildDisplayPreferencesDataStore.edit { values ->
            values[HIDE_LIBRARY_CATEGORY_PREFIX] = enabled
        }
    }

    private companion object {
        val COMPACT_MEDIA_ROWS = booleanPreferencesKey("compact_media_rows")
        val SHOW_CHANNEL_LOGOS = booleanPreferencesKey("show_channel_logos")
        val PREFER_TVG_NAME = booleanPreferencesKey("prefer_tvg_name")
        val HIDE_CHANNEL_PREFIX = booleanPreferencesKey("hide_channel_prefix")
        val SHOW_CATEGORY_FLAGS = booleanPreferencesKey("show_category_flags")
        val HIDE_LIVE_CATEGORY_PREFIX = booleanPreferencesKey("hide_live_category_prefix")
        val HIDE_LIBRARY_CATEGORY_PREFIX = booleanPreferencesKey("hide_library_category_prefix")
        val LEGACY_HIDE_CATEGORY_PREFIX = booleanPreferencesKey("hide_category_prefix")
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

    override suspend fun setShowCategoryFlags(enabled: Boolean): Boolean = write {
        store.setShowCategoryFlags(enabled)
    }

    override suspend fun setHideLiveCategoryPrefix(enabled: Boolean): Boolean = write {
        store.setHideLiveCategoryPrefix(enabled)
    }

    override suspend fun setHideLibraryCategoryPrefix(enabled: Boolean): Boolean = write {
        store.setHideLibraryCategoryPrefix(enabled)
    }

    private suspend fun write(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (_: Exception) {
            false
        }
}
