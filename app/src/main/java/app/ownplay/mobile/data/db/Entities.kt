package app.ownplay.mobile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "sources")
data class SourceEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val displayName: String,
    val type: String,
    val baseLocator: String,
    val credentialReference: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "provider_categories",
    primaryKeys = ["sourceId", "kind", "categoryKey"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class ProviderCategoryEntity(
    val sourceId: String,
    val kind: String,
    val categoryKey: String,
    val providerKey: String,
    val name: String,
    val providerOrder: Int,
    val available: Boolean,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "category_personalization",
    primaryKeys = ["sourceId", "kind", "categoryKey"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class CategoryPersonalizationEntity(
    val sourceId: String,
    val kind: String,
    val categoryKey: String,
    val hidden: Boolean = false,
    val manualOrder: Int? = null,
)

@Entity(
    tableName = "live_channels",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index(value = ["sourceId", "categoryKey"])],
)
data class LiveChannelEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val sourceId: String,
    val providerKey: String,
    val providerStreamId: String?,
    val categoryKey: String?,
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val streamLocator: String,
    val providerOrder: Int,
    val available: Boolean,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "channel_personalization",
    foreignKeys = [ForeignKey(entity = LiveChannelEntity::class, parentColumns = ["channelId"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE)],
)
data class ChannelPersonalizationEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val localName: String? = null,
    val localLogo: String? = null,
    val manualOrder: Int? = null,
)

@Entity(
    tableName = "custom_groups",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId")],
)
data class CustomGroupEntity(
    @androidx.room.PrimaryKey val groupId: String,
    val sourceId: String,
    val name: String,
    val manualOrder: Int,
)

@Entity(
    tableName = "custom_group_memberships",
    primaryKeys = ["groupId", "channelId"],
    foreignKeys = [
        ForeignKey(entity = CustomGroupEntity::class, parentColumns = ["groupId"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LiveChannelEntity::class, parentColumns = ["channelId"], childColumns = ["channelId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("channelId")],
)
data class CustomGroupMembershipEntity(
    val groupId: String,
    val channelId: String,
    val manualOrder: Int,
)

@Entity(
    tableName = "movies",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index(value = ["sourceId", "categoryKey"])],
)
data class MovieEntity(
    @androidx.room.PrimaryKey val movieId: String,
    val sourceId: String,
    val providerStreamId: String,
    val categoryKey: String?,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val extension: String?,
    val rating: String?,
    val providerOrder: Int,
    val available: Boolean,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "series",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index(value = ["sourceId", "categoryKey"])],
)
data class SeriesEntity(
    @androidx.room.PrimaryKey val seriesId: String,
    val sourceId: String,
    val providerSeriesId: String,
    val categoryKey: String?,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val rating: String?,
    val providerOrder: Int,
    val available: Boolean,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(entity = SeriesEntity::class, parentColumns = ["seriesId"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId"), Index(value = ["seriesId", "seasonNumber", "episodeNumber"])],
)
data class EpisodeEntity(
    @androidx.room.PrimaryKey val episodeId: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val providerEpisodeId: String,
    val title: String,
    val durationMs: Long?,
    val streamLocator: String,
    val extension: String?,
    val available: Boolean,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "media_favorites",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class MediaFavoriteEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "playback_progress",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class PlaybackProgressEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "downloads",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index(value = ["sourceId", "mediaKind", "contentId"])],
)
data class DownloadEntity(
    @androidx.room.PrimaryKey val downloadId: String,
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val title: String,
    val streamIdentity: String,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localReference: String?,
    val integrityMetadata: String?,
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "refresh_state",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class RefreshStateEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val generation: Long,
    val state: String,
    val lastAttempt: Long,
    val lastSuccess: Long?,
    val errorCode: String?,
)
