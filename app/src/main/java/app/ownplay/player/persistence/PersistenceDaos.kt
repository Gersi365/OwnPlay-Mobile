package app.ownplay.player.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.ownplay.player.persistence.vod.ProviderMovieEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistSourceDao {
    @Upsert
    suspend fun upsert(source: PlaylistSourceEntity)

    @Query("SELECT * FROM playlist_sources WHERE enabled = 1 ORDER BY createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<PlaylistSourceEntity>>

    @Query("SELECT * FROM playlist_sources ORDER BY createdAtEpochMillis ASC, sourceId ASC")
    suspend fun allForBackup(): List<PlaylistSourceEntity>

    @Query(
        """
        SELECT
            source.sourceId AS sourceId,
            source.name AS name,
            source.sourceKind AS sourceKind,
            source.enabled AS enabled,
            CAST(COUNT(channel.channelId) AS INTEGER) AS channelCount,
            source.createdAtEpochMillis AS createdAtEpochMillis,
            source.updatedAtEpochMillis AS updatedAtEpochMillis
        FROM playlist_sources AS source
        LEFT JOIN provider_channels AS channel
            ON channel.sourceId = source.sourceId
            AND channel.availability != 'removed'
        GROUP BY source.sourceId
        ORDER BY source.createdAtEpochMillis ASC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSourceSummary>>

    @Query("SELECT * FROM playlist_sources WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getById(sourceId: String): PlaylistSourceEntity?

    @Query("DELETE FROM playlist_sources WHERE sourceId = :sourceId")
    suspend fun deleteById(sourceId: String): Int
}

@Dao
interface ProviderCatalogDao {
    @Upsert
    suspend fun upsertCategories(categories: List<ProviderCategoryEntity>)

    @Query("DELETE FROM provider_categories WHERE sourceId = :sourceId")
    suspend fun deleteCategoriesForSource(sourceId: String): Int

    @Upsert
    suspend fun upsertChannels(channels: List<ProviderChannelEntity>)

    @Query(
        """
        UPDATE provider_channels
        SET availability = 'removed'
        WHERE sourceId = :sourceId
            AND lastSeenGeneration != :generation
            AND availability != 'removed'
        """,
    )
    suspend fun markChannelsMissingFromGeneration(
        sourceId: String,
        generation: Long,
    )

    @Query(
        "SELECT * FROM provider_channels " +
            "WHERE sourceId = :sourceId ORDER BY providerOrder ASC",
    )
    suspend fun channelsForSource(sourceId: String): List<ProviderChannelEntity>

    @Query(
        "SELECT * FROM provider_channels " +
            "WHERE sourceId = :sourceId AND providerKey = :providerKey LIMIT 1",
    )
    suspend fun channelByProviderKey(
        sourceId: String,
        providerKey: String,
    ): ProviderChannelEntity?

    @Query("SELECT * FROM provider_channels WHERE channelId = :channelId LIMIT 1")
    suspend fun channelById(channelId: String): ProviderChannelEntity?

    @Query("SELECT * FROM provider_movies WHERE movieId = :movieId LIMIT 1")
    suspend fun movieById(movieId: String): ProviderMovieEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM provider_categories
            WHERE sourceId = :sourceId
                AND providerCategoryKey = :providerCategoryKey
        )
        """,
    )
    suspend fun categoryExistsInSource(
        sourceId: String,
        providerCategoryKey: String,
    ): Boolean
}

@Dao
interface PersonalizationDao {
    @Upsert
    suspend fun upsertCustomization(customization: ChannelCustomizationEntity)

    @Upsert
    suspend fun upsertCustomizations(customizations: List<ChannelCustomizationEntity>)

    @Query(
        """
        SELECT channel.channelId
        FROM provider_channels AS channel
        LEFT JOIN channel_customizations AS customization
            ON customization.channelId = channel.channelId
        WHERE channel.sourceId = :sourceId
        ORDER BY
            customization.manualOrder IS NULL ASC,
            customization.manualOrder ASC,
            channel.providerOrder ASC,
            channel.channelId ASC
        """,
    )
    suspend fun resolvedChannelOrder(sourceId: String): List<String>

    @Query(
        """
        SELECT customization.*
        FROM channel_customizations AS customization
        INNER JOIN provider_channels AS channel
            ON channel.channelId = customization.channelId
        WHERE channel.sourceId = :sourceId
        """,
    )
    suspend fun customizationsForSource(sourceId: String): List<ChannelCustomizationEntity>

    @Query(
        """
        SELECT customization.*
        FROM channel_customizations AS customization
        INNER JOIN provider_channels AS channel
            ON channel.channelId = customization.channelId
        WHERE channel.sourceId = :sourceId AND channel.channelId = :channelId
        LIMIT 1
        """,
    )
    suspend fun customizationForChannel(
        sourceId: String,
        channelId: String,
    ): ChannelCustomizationEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM provider_channels
            WHERE sourceId = :sourceId AND channelId = :channelId
        )
        """,
    )
    suspend fun channelExistsInSource(
        sourceId: String,
        channelId: String,
    ): Boolean

    @Upsert
    suspend fun upsertHidden(entry: HiddenEntryEntity)

    @Upsert
    suspend fun upsertHidden(entries: List<HiddenEntryEntity>)

    @Query(
        """
        SELECT hidden.*
        FROM hidden_entries AS hidden
        INNER JOIN provider_channels AS channel
            ON channel.channelId = hidden.channelId
        WHERE channel.sourceId = :sourceId
        ORDER BY hidden.hiddenAtEpochMillis ASC, hidden.channelId ASC
        """,
    )
    suspend fun hiddenEntriesForSource(sourceId: String): List<HiddenEntryEntity>

    @Query("DELETE FROM hidden_entries WHERE channelId = :channelId")
    suspend fun unhide(channelId: String)

    @Upsert
    suspend fun upsertFavorite(entry: FavoriteEntryEntity)

    @Upsert
    suspend fun upsertFavorites(entries: List<FavoriteEntryEntity>)

    @Query(
        """
        SELECT favorite.*
        FROM favorite_entries AS favorite
        INNER JOIN provider_channels AS channel
            ON channel.channelId = favorite.channelId
        WHERE channel.sourceId = :sourceId
        ORDER BY favorite.favoriteOrder ASC, favorite.addedAtEpochMillis ASC, favorite.channelId ASC
        """,
    )
    suspend fun favoriteEntriesForSource(sourceId: String): List<FavoriteEntryEntity>

    @Query("DELETE FROM favorite_entries WHERE channelId = :channelId")
    suspend fun removeFavorite(channelId: String)

    @Upsert
    suspend fun upsertGroup(group: CustomGroupEntity)

    @Query("SELECT * FROM custom_groups ORDER BY groupOrder ASC, createdAtEpochMillis ASC, groupId ASC")
    suspend fun customGroupsForMutation(): List<CustomGroupEntity>

    @Query("SELECT * FROM custom_groups WHERE groupId = :groupId LIMIT 1")
    suspend fun customGroupById(groupId: String): CustomGroupEntity?

    @Query("DELETE FROM custom_groups WHERE groupId = :groupId")
    suspend fun deleteCustomGroup(groupId: String): Int

    @Upsert
    suspend fun upsertGroupMembership(membership: CustomGroupMembershipEntity)

    @Upsert
    suspend fun upsertGroupMemberships(memberships: List<CustomGroupMembershipEntity>)

    @Query(
        "SELECT * FROM custom_group_memberships " +
            "WHERE groupId = :groupId ORDER BY groupOrder ASC, channelId ASC",
    )
    suspend fun groupMemberships(groupId: String): List<CustomGroupMembershipEntity>

    @Query("DELETE FROM custom_group_memberships WHERE groupId = :groupId AND channelId = :channelId")
    suspend fun removeGroupMembership(
        groupId: String,
        channelId: String,
    )

    @Query("SELECT * FROM favorite_entries ORDER BY favoriteOrder ASC")
    fun observeFavorites(): Flow<List<FavoriteEntryEntity>>

    @Query("SELECT * FROM custom_groups ORDER BY groupOrder ASC")
    fun observeGroups(): Flow<List<CustomGroupEntity>>
}

@Dao
interface RefreshStateDao {
    @Upsert
    suspend fun upsert(state: PlaylistRefreshStateEntity)

    @Query("SELECT * FROM playlist_refresh_state WHERE sourceId = :sourceId LIMIT 1")
    suspend fun get(sourceId: String): PlaylistRefreshStateEntity?
}
