package app.ownplay.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface V2SourceDao {
    @Query("SELECT * FROM v2_sources ORDER BY createdAtEpochMillis, sourceId")
    fun observeSources(): Flow<List<V2SourceEntity>>

    @Query("SELECT * FROM v2_sources ORDER BY createdAtEpochMillis, sourceId")
    suspend fun getSources(): List<V2SourceEntity>

    @Query("SELECT * FROM v2_sources WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getSource(sourceId: String): V2SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: V2SourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<V2SourceEntity>)

    @Query("DELETE FROM v2_sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)

    @Query(
        "UPDATE v2_sources SET refreshGeneration = :generation, lastRefreshAtEpochMillis = :atMillis, " +
            "lastRefreshError = NULL, updatedAtEpochMillis = :atMillis WHERE sourceId = :sourceId",
    )
    suspend fun recordRefreshSuccess(sourceId: String, generation: Long, atMillis: Long)

    @Query(
        "UPDATE v2_sources SET lastRefreshAtEpochMillis = :atMillis, lastRefreshError = :reason, " +
            "updatedAtEpochMillis = :atMillis WHERE sourceId = :sourceId",
    )
    suspend fun recordRefreshFailure(sourceId: String, reason: String, atMillis: Long)
}

@Dao
interface V2CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<V2CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<V2MediaItemEntity>)

    @Query(
        "UPDATE v2_categories SET available = 0 WHERE sourceId = :sourceId " +
            "AND lastSeenGeneration < :generation",
    )
    suspend fun markUnseenCategoriesUnavailable(sourceId: String, generation: Long)

    @Query(
        "UPDATE v2_items SET available = 0 WHERE sourceId = :sourceId " +
            "AND lastSeenGeneration < :generation",
    )
    suspend fun markUnseenItemsUnavailable(sourceId: String, generation: Long)

    @Query(
        "SELECT * FROM v2_items WHERE sourceId = :sourceId AND catalogType = :catalogType " +
            "AND available = 1 ORDER BY providerOrder, name, itemId",
    )
    fun observeAvailableItems(sourceId: String, catalogType: String): Flow<List<V2MediaItemEntity>>
}

@Dao
interface V2PersonalizationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItemPersonalization(items: List<V2ItemPersonalizationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(groups: List<V2CustomGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupMemberships(items: List<V2CustomGroupMembershipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaFavorites(items: List<V2MediaFavoriteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackProgress(items: List<V2PlaybackProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadMetadata(items: List<V2DownloadMetadataEntity>)
}

@Dao
interface V2MigrationDao {
    @Query("SELECT * FROM v2_migration_state WHERE migrationId = :migrationId LIMIT 1")
    suspend fun get(migrationId: String): V2MigrationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: V2MigrationStateEntity)
}
