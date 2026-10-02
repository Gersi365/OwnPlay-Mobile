package app.ownplay.mobile.feature.settings.domain

import kotlinx.coroutines.flow.Flow

data class DisplayPreferences(
    val compactMediaRows: Boolean = false,
    val showChannelLogos: Boolean = true,
    val preferTvgName: Boolean = false,
    val hideChannelPrefix: Boolean = true,
    val showCategoryFlags: Boolean = true,
    val hideLiveCategoryPrefix: Boolean = false,
    val hideLibraryCategoryPrefix: Boolean = false,
)

interface DisplayPreferencesRepository {
    val preferences: Flow<DisplayPreferences>

    suspend fun setCompactMediaRows(enabled: Boolean): Boolean

    suspend fun setShowChannelLogos(enabled: Boolean): Boolean

    suspend fun setPreferTvgName(enabled: Boolean): Boolean

    suspend fun setHideChannelPrefix(enabled: Boolean): Boolean

    suspend fun setShowCategoryFlags(enabled: Boolean): Boolean

    suspend fun setHideLiveCategoryPrefix(enabled: Boolean): Boolean

    suspend fun setHideLibraryCategoryPrefix(enabled: Boolean): Boolean
}
