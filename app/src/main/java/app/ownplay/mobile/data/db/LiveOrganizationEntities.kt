package app.ownplay.mobile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "live_organization_preferences",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LiveOrganizationPreferenceEntity(
    @androidx.room.PrimaryKey val sourceId: String,
    val activeMode: String = "PROVIDER",
)

@Entity(
    tableName = "ownplay_live_categories",
    primaryKeys = ["sourceId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "parentCategoryId"]),
    ],
)
data class OwnPlayLiveCategoryEntity(
    val sourceId: String,
    val categoryId: String,
    val parentCategoryId: String? = null,
    val displayName: String,
    val semanticKey: String? = null,
    val origin: String,
    val available: Boolean = true,
    val lastSeenGeneration: Long = 0,
)

@Entity(
    tableName = "live_category_scope_personalization",
    primaryKeys = ["sourceId", "organizationMode", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "organizationMode"]),
    ],
)
data class LiveCategoryScopePersonalizationEntity(
    val sourceId: String,
    val organizationMode: String,
    val categoryId: String,
    val hidden: Boolean = false,
    val manualOrder: Int? = null,
)

@Entity(
    tableName = "ownplay_live_channel_memberships",
    primaryKeys = ["sourceId", "categoryId", "channelId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OwnPlayLiveCategoryEntity::class,
            parentColumns = ["sourceId", "categoryId"],
            childColumns = ["sourceId", "categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LiveChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index("channelId"),
        Index(value = ["sourceId", "categoryId"]),
    ],
)
data class OwnPlayLiveChannelMembershipEntity(
    val sourceId: String,
    val categoryId: String,
    val channelId: String,
    val included: Boolean = true,
    val origin: String,
    val confidence: String? = null,
    val evidenceJson: String? = null,
    val available: Boolean = true,
    val lastSeenGeneration: Long = 0,
)

@Entity(
    tableName = "live_channel_membership_personalization",
    primaryKeys = ["sourceId", "organizationMode", "categoryId", "channelId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LiveChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index("channelId"),
        Index(value = ["sourceId", "organizationMode", "categoryId"]),
    ],
)
data class LiveChannelMembershipPersonalizationEntity(
    val sourceId: String,
    val organizationMode: String,
    val categoryId: String,
    val channelId: String,
    val hidden: Boolean = false,
    val manualOrder: Int? = null,
)
