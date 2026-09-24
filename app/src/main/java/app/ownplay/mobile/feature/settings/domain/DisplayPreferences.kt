package app.ownplay.mobile.feature.settings.domain

import kotlinx.coroutines.flow.Flow

data class DisplayPreferences(
    val compactMediaRows: Boolean = false,
    val showChannelLogos: Boolean = true,
    val preferTvgName: Boolean = false,
    val hideChannelPrefix: Boolean = true,
    val hideCategoryPrefix: Boolean = true,
)

interface DisplayPreferencesRepository {
    val preferences: Flow<DisplayPreferences>

    suspend fun setCompactMediaRows(enabled: Boolean): Boolean

    suspend fun setShowChannelLogos(enabled: Boolean): Boolean

    suspend fun setPreferTvgName(enabled: Boolean): Boolean

    suspend fun setHideChannelPrefix(enabled: Boolean): Boolean

    suspend fun setHideCategoryPrefix(enabled: Boolean): Boolean
}
