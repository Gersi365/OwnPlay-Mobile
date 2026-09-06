package app.ownplay.player.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

object SourceKinds {
    const val XTREAM = "xtream"
    const val REMOTE_M3U = "remote_m3u"
    const val LOCAL_M3U = "local_m3u"
}

object ChannelAvailability {
    const val AVAILABLE = "available"
    const val TEMPORARILY_UNAVAILABLE = "temporarily_unavailable"
    const val REMOVED = "removed"
}

object RefreshStates {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
}

@Entity(tableName = "playlist_sources")
data class PlaylistSourceEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val name: String,
    val sourceKind: String,
    val locatorRef: String,
    val credentialRef: String? = null,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    override fun toString(): String =
        "PlaylistSourceEntity(sourceId=$sourceId, name=$name, sourceKind=$sourceKind, " +
            "locatorRef=<opaque>, credentialRef=${if (credentialRef == null) "null" else "<opaque>"}, " +
            "enabled=$enabled)"
}

@Entity(
    tableName = "provider_categories",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "providerCategoryKey"], unique = true),
    ],
)
data class ProviderCategoryEntity(
    @androidx.room.PrimaryKey val categoryId: String,
    val sourceId: String,
    val providerCategoryKey: String,
    val name: String,
    val parentProviderKey: String? = null,
    val providerOrder: Long,
)

@Entity(
    tableName = "provider_channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "providerKey"], unique = true),
        Index(value = ["sourceId", "providerCategoryKey"]),
        Index(value = ["sourceId", "availability"]),
    ],
)
data class ProviderChannelEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val sourceId: String,
    val providerKey: String,
    val providerStreamId: String? = null,
    val providerCategoryKey: String? = null,
    val providerName: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoRef: String? = null,
    val streamLocatorRef: String,
    val providerOrder: Long,
    val availability: String = ChannelAvailability.AVAILABLE,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "channel_customizations",
    foreignKeys = [
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelCustomizationEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val localDisplayName: String? = null,
    val logoOverrideRef: String? = null,
    val manualOrder: Long? = null,
)

@Entity(
    tableName = "hidden_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HiddenEntryEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val hiddenAtEpochMillis: Long,
)

@Entity(
    tableName = "favorite_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["favoriteOrder"])],
)
data class FavoriteEntryEntity(
    @androidx.room.PrimaryKey val channelId: String,
    val favoriteOrder: Long,
    val addedAtEpochMillis: Long,
)

@Entity(
    tableName = "custom_groups",
    indices = [Index(value = ["groupOrder"])],
)
data class CustomGroupEntity(
    @androidx.room.PrimaryKey val groupId: String,
    val name: String,
    val groupOrder: Long,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "custom_group_memberships",
    primaryKeys = ["groupId", "channelId"],
    foreignKeys = [
        ForeignKey(
            entity = CustomGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["channelId"])],
)
data class CustomGroupMembershipEntity(
    val groupId: String,
    val channelId: String,
    val groupOrder: Long,
)

@Entity(
    tableName = "playlist_refresh_state",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistSourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistRefreshStateEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val generation: Long,
    val state: String = RefreshStates.IDLE,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val lastErrorCode: String? = null,
)
