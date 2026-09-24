package app.ownplay.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

data class BackupChannelPersonalizationRow(
    val sourceId: String,
    val channelId: String,
    val favorite: Boolean,
    val hidden: Boolean,
    val localName: String?,
    val localLogo: String?,
    val manualOrder: Int?,
)

@Dao
interface BackupDao {
    @Query("SELECT * FROM category_personalization ORDER BY sourceId, kind, categoryKey")
    suspend fun getCategoryPersonalization(): List<CategoryPersonalizationEntity>

    @Query(
        """
        SELECT c.sourceId, p.channelId, p.favorite, p.hidden,
               p.localName, p.localLogo, p.manualOrder
        FROM channel_personalization p
        INNER JOIN live_channels c ON c.channelId = p.channelId
        ORDER BY c.sourceId, p.channelId
        """,
    )
    suspend fun getChannelPersonalization(): List<BackupChannelPersonalizationRow>

    @Query("SELECT * FROM media_favorites ORDER BY sourceId, mediaKind, contentId")
    suspend fun getMediaFavorites(): List<MediaFavoriteEntity>

    @Query("SELECT * FROM live_organization_preferences ORDER BY sourceId")
    suspend fun getLivePreferences(): List<LiveOrganizationPreferenceEntity>

    @Query(
        """
        SELECT * FROM live_category_scope_personalization
        ORDER BY sourceId, organizationMode, categoryId
        """,
    )
    suspend fun getLiveCategoryPersonalization(): List<LiveCategoryScopePersonalizationEntity>

    @Query(
        """
        SELECT * FROM live_channel_membership_personalization
        WHERE organizationMode = 'OWNPLAY_OVERRIDE'
        ORDER BY sourceId, categoryId, channelId
        """,
    )
    suspend fun getManualPlacementOverrides(): List<LiveChannelMembershipPersonalizationEntity>

    @Query("SELECT * FROM custom_groups ORDER BY sourceId, manualOrder, groupId")
    suspend fun getCustomGroups(): List<CustomGroupEntity>

    @Query(
        """
        SELECT m.* FROM custom_group_memberships m
        INNER JOIN custom_groups g ON g.groupId = m.groupId
        ORDER BY g.sourceId, m.groupId, m.manualOrder, m.channelId
        """,
    )
    suspend fun getCustomGroupMemberships(): List<CustomGroupMembershipEntity>

    @Query("SELECT channelId FROM live_channels WHERE sourceId = :sourceId")
    suspend fun getChannelIds(sourceId: String): List<String>

    @Query(
        """
        SELECT categoryKey FROM provider_categories
        WHERE sourceId = :sourceId
          AND kind = :kind
        """,
    )
    suspend fun getProviderCategoryKeys(
        sourceId: String,
        kind: String,
    ): List<String>

    @Query("SELECT categoryId FROM ownplay_live_categories WHERE sourceId = :sourceId")
    suspend fun getOwnPlayCategoryIds(sourceId: String): List<String>

    @Query("SELECT movieId FROM movies WHERE sourceId = :sourceId")
    suspend fun getMovieIds(sourceId: String): List<String>

    @Query("SELECT seriesId FROM series WHERE sourceId = :sourceId")
    suspend fun getSeriesIds(sourceId: String): List<String>

    @Query("SELECT groupId FROM custom_groups WHERE sourceId = :sourceId")
    suspend fun getGroupIds(sourceId: String): List<String>

    @Query("SELECT sourceId FROM custom_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupSourceId(groupId: String): String?

    @Upsert
    suspend fun upsertCategoryPersonalization(rows: List<CategoryPersonalizationEntity>)

    @Upsert
    suspend fun upsertChannelPersonalization(rows: List<ChannelPersonalizationEntity>)

    @Upsert
    suspend fun upsertMediaFavorites(rows: List<MediaFavoriteEntity>)

    @Upsert
    suspend fun upsertLivePreferences(rows: List<LiveOrganizationPreferenceEntity>)

    @Upsert
    suspend fun upsertLiveCategoryPersonalization(rows: List<LiveCategoryScopePersonalizationEntity>)

    @Upsert
    suspend fun upsertManualPlacementOverrides(rows: List<LiveChannelMembershipPersonalizationEntity>)

    @Upsert
    suspend fun upsertCustomGroups(rows: List<CustomGroupEntity>)

    @Upsert
    suspend fun upsertCustomGroupMemberships(rows: List<CustomGroupMembershipEntity>)
}
