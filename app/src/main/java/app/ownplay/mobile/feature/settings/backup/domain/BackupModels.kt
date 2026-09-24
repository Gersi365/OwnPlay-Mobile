package app.ownplay.mobile.feature.settings.backup.domain

import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.feature.live.domain.LiveOrganizationMode
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceType

object BackupFormatContract {
    const val FORMAT = "ownplay-backup"
    const val VERSION = 1
}

data class OwnPlayBackupEnvelope(
    val format: String = BackupFormatContract.FORMAT,
    val version: Int = BackupFormatContract.VERSION,
    val createdAt: String,
    val payload: OwnPlayBackupPayload,
)

data class OwnPlayBackupPayload(
    val sources: List<BackupSourceDefinition> = emptyList(),
    val activeSourceId: String? = null,
    val globalSettings: BackupGlobalSettings = BackupGlobalSettings(),
    val sourceSettings: List<BackupSourceSettings> = emptyList(),
    val categoryPersonalization: List<BackupCategoryPersonalization> = emptyList(),
    val channelPersonalization: List<BackupChannelPersonalization> = emptyList(),
    val mediaFavorites: List<BackupMediaFavorite> = emptyList(),
    val liveCategoryPersonalization: List<BackupLiveCategoryPersonalization> = emptyList(),
    val livePlacementOverrides: List<BackupLivePlacementOverride> = emptyList(),
    val customGroups: List<BackupCustomGroup> = emptyList(),
    val customGroupMemberships: List<BackupCustomGroupMembership> = emptyList(),
)

data class BackupSourceDefinition(
    val sourceId: String,
    val type: SourceType,
    val displayName: String,
    val baseLocator: String,
    val enabled: Boolean,
)

data class BackupGlobalSettings(
    val display: DisplayPreferences = DisplayPreferences(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val downloads: DownloadPreferences = DownloadPreferences(),
)

data class BackupSourceSettings(
    val sourceId: String,
    val refreshSchedule: SourceRefreshSchedule = SourceRefreshSchedule.MANUAL,
    val refreshWifiOnly: Boolean = false,
    val liveOrganizationMode: LiveOrganizationMode = LiveOrganizationMode.PROVIDER,
)

enum class BackupCatalogKind {
    LIVE,
    MOVIE,
    SERIES,
}

data class BackupCategoryPersonalization(
    val sourceId: String,
    val kind: BackupCatalogKind,
    val categoryKey: String,
    val hidden: Boolean,
    val manualOrder: Int?,
)

data class BackupChannelPersonalization(
    val sourceId: String,
    val channelId: String,
    val favorite: Boolean,
    val hidden: Boolean,
    val localName: String?,
    val localLogo: String?,
    val manualOrder: Int?,
)

enum class BackupMediaKind {
    MOVIE,
    SERIES,
}

data class BackupMediaFavorite(
    val sourceId: String,
    val mediaKind: BackupMediaKind,
    val contentId: String,
    val addedAt: Long,
)

data class BackupLiveCategoryPersonalization(
    val sourceId: String,
    val organizationMode: LiveOrganizationMode,
    val categoryId: String,
    val hidden: Boolean,
    val manualOrder: Int?,
)

data class BackupLivePlacementOverride(
    val sourceId: String,
    val categoryId: String,
    val channelId: String,
    val hidden: Boolean,
    val manualOrder: Int?,
)

data class BackupCustomGroup(
    val sourceId: String,
    val groupId: String,
    val name: String,
    val manualOrder: Int,
)

data class BackupCustomGroupMembership(
    val groupId: String,
    val channelId: String,
    val manualOrder: Int,
)
