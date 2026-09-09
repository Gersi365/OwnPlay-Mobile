package app.ownplay.mobile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "v2_sources")
data class V2SourceEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val name: String,
    val kind: String,
    val locator: String,
    val enabled: Boolean,
    val credentialState: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val refreshGeneration: Long,
    val lastRefreshAtEpochMillis: Long?,
    val lastRefreshError: String?,
)

@Entity(
    tableName = "v2_categories",
    foreignKeys = [
        ForeignKey(
            entity = V2SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "catalogType", "providerKey"], unique = true),
    ],
)
data class V2CategoryEntity(
    @androidx.room.PrimaryKey val categoryId: String,
    val sourceId: String,
    val catalogType: String,
    val providerKey: String,
    val name: String,
    val providerOrder: Int,
    val lastSeenGeneration: Long,
    val available: Boolean,
)

@Entity(
    tableName = "v2_items",
    foreignKeys = [
        ForeignKey(
            entity = V2SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "catalogType", "providerKey"], unique = true),
        Index(value = ["sourceId", "catalogType", "available", "providerOrder"]),
    ],
)
data class V2MediaItemEntity(
    @androidx.room.PrimaryKey val itemId: String,
    val sourceId: String,
    val catalogType: String,
    val providerKey: String,
    val categoryKey: String?,
    val name: String,
    val artworkUrl: String?,
    val streamLocator: String?,
    val containerExtension: String?,
    val rating: Double?,
    val providerOrder: Int,
    val lastSeenGeneration: Long,
    val available: Boolean,
)

@Entity(
    tableName = "v2_item_personalization",
    foreignKeys = [
        ForeignKey(
            entity = V2MediaItemEntity::class,
            parentColumns = ["itemId"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class V2ItemPersonalizationEntity(
    @androidx.room.PrimaryKey val itemId: String,
    val localName: String?,
    val artworkOverride: String?,
    val hidden: Boolean,
    val favorite: Boolean,
    val manualOrder: Int?,
    val favoriteOrder: Int?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "v2_custom_groups",
    indices = [Index(value = ["groupOrder"])],
)
data class V2CustomGroupEntity(
    @androidx.room.PrimaryKey val groupId: String,
    val name: String,
    val groupOrder: Int,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "v2_custom_group_memberships",
    primaryKeys = ["groupId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = V2CustomGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = V2MediaItemEntity::class,
            parentColumns = ["itemId"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"])],
)
data class V2CustomGroupMembershipEntity(
    val groupId: String,
    val itemId: String,
    val groupOrder: Int,
)

@Entity(
    tableName = "v2_media_favorites",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
    indices = [Index(value = ["mediaKind", "addedAtEpochMillis"])],
)
data class V2MediaFavoriteEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val addedAtEpochMillis: Long,
)

@Entity(
    tableName = "v2_playback_progress",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
    indices = [Index(value = ["mediaKind", "updatedAtEpochMillis"])],
)
data class V2PlaybackProgressEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "v2_download_metadata",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["state", "createdAtEpochMillis"]),
        Index(value = ["sourceId", "mediaKind", "contentId"], unique = true),
    ],
)
data class V2DownloadMetadataEntity(
    @androidx.room.PrimaryKey val downloadId: String,
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val providerStreamId: Long?,
    val title: String,
    val seriesTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val posterUrl: String?,
    val containerExtension: String?,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val localRelativePath: String?,
    val failureReason: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "v2_migration_state")
data class V2MigrationStateEntity(
    @androidx.room.PrimaryKey val migrationId: String,
    val state: String,
    val completedAtEpochMillis: Long?,
    val details: String?,
)
