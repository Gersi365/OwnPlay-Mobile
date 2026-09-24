package app.ownplay.mobile.downloads.domain

import kotlinx.coroutines.flow.Flow

data class DownloadPreferences(
    val unmeteredNetworkOnly: Boolean = false,
    val destinationRelativePath: String = DownloadDestinationPolicy.DEFAULT_DESTINATION,
    val notificationsEnabled: Boolean = true,
) {
    val wifiOnly: Boolean
        get() = unmeteredNetworkOnly
}

interface DownloadPreferencesRepository {
    val preferences: Flow<DownloadPreferences>

    suspend fun current(): DownloadPreferences

    suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean

    suspend fun setDestinationRelativePath(relativePath: String): Boolean

    suspend fun setNotificationsEnabled(enabled: Boolean): Boolean
}

object DownloadNetworkPreferencePolicy {
    fun requiresUnmeteredNetwork(preferences: DownloadPreferences): Boolean =
        preferences.wifiOnly
}

object DownloadDestinationPolicy {
    const val DEFAULT_DESTINATION = "Download/OwnPlay Downloads/"

    fun normalize(value: String): String? {
        val normalized = value.trim().replace('\\', '/').trim('/')
        if (normalized.isBlank()) return null
        val segments = normalized.split('/').map(String::trim)
        if (segments.isEmpty() || !segments.first().equals("Download", ignoreCase = true)) return null
        if (segments.any { segment ->
                segment.isBlank() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.any(Char::isISOControl) ||
                    ':' in segment
            }
        ) return null
        return (listOf("Download") + segments.drop(1)).joinToString("/") + "/"
    }
}
