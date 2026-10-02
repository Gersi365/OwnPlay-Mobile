package app.ownplay.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY updatedAt DESC, createdAt ASC, sourceId ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE sourceId = :sourceId LIMIT 1")
    suspend fun get(sourceId: String): SourceEntity?

    @Query("SELECT * FROM sources ORDER BY updatedAt DESC, createdAt ASC, sourceId ASC")
    suspend fun getAll(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SourceEntity)

    @Update
    suspend fun update(entity: SourceEntity)

    @Query("DELETE FROM sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String): Int
}

@Dao
interface RefreshStateDao {
    @Query("SELECT * FROM refresh_state ORDER BY sourceId ASC")
    fun observeAll(): Flow<List<RefreshStateEntity>>

    @Query("SELECT * FROM refresh_state WHERE sourceId = :sourceId LIMIT 1")
    suspend fun get(sourceId: String): RefreshStateEntity?

    @Upsert
    suspend fun upsert(entity: RefreshStateEntity)

    @Query("SELECT * FROM provider_categories WHERE sourceId = :sourceId")
    suspend fun getCategoriesForRefresh(sourceId: String): List<ProviderCategoryEntity>

    @Query("SELECT * FROM live_channels WHERE sourceId = :sourceId")
    suspend fun getLiveChannelsForRefresh(sourceId: String): List<LiveChannelEntity>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId")
    suspend fun getMoviesForRefresh(sourceId: String): List<MovieEntity>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId")
    suspend fun getSeriesForRefresh(sourceId: String): List<SeriesEntity>

    @Upsert
    suspend fun upsertCategories(rows: List<ProviderCategoryEntity>)

    @Upsert
    suspend fun upsertLiveChannels(rows: List<LiveChannelEntity>)

    @Upsert
    suspend fun upsertMovies(rows: List<MovieEntity>)

    @Upsert
    suspend fun upsertSeries(rows: List<SeriesEntity>)

    @Query(
        """
        UPDATE provider_categories
        SET available = 0
        WHERE sourceId = :sourceId
          AND kind = :kind
          AND lastSeenGeneration != :generation
        """,
    )
    suspend fun markMissingCategoriesUnavailable(
        sourceId: String,
        kind: String,
        generation: Long,
    )

    @Query(
        """
        UPDATE live_channels
        SET available = 0
        WHERE sourceId = :sourceId
          AND lastSeenGeneration != :generation
        """,
    )
    suspend fun markMissingLiveUnavailable(sourceId: String, generation: Long)

    @Query(
        """
        UPDATE movies
        SET available = 0
        WHERE sourceId = :sourceId
          AND lastSeenGeneration != :generation
        """,
    )
    suspend fun markMissingMoviesUnavailable(sourceId: String, generation: Long)

    @Query(
        """
        UPDATE series
        SET available = 0
        WHERE sourceId = :sourceId
          AND lastSeenGeneration != :generation
        """,
    )
    suspend fun markMissingSeriesUnavailable(sourceId: String, generation: Long)
}

@Dao
interface DownloadDao {
    @Query(
        """
        SELECT * FROM downloads
        WHERE sourceId = :sourceId
        ORDER BY createdAt DESC, downloadId ASC
        """,
    )
    fun observeForSource(sourceId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    fun observe(downloadId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun get(downloadId: String): DownloadEntity?

    @Query(
        """
        SELECT * FROM downloads
        WHERE state IN ('WAITING_FOR_WIFI', 'QUEUED', 'DOWNLOADING')
        ORDER BY createdAt ASC, downloadId ASC
        """,
    )
    suspend fun getExecutionQueue(): List<DownloadEntity>

    @Query("SELECT downloadId FROM downloads WHERE sourceId = :sourceId ORDER BY createdAt ASC, downloadId ASC")
    suspend fun getIdsForSource(sourceId: String): List<String>

    @Query(
        """
        SELECT * FROM downloads
        WHERE sourceId = :sourceId
          AND mediaKind = :mediaKind
          AND contentId = :contentId
        ORDER BY updatedAt DESC, downloadId ASC
        LIMIT 1
        """,
    )
    suspend fun getForContent(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE downloadId = :downloadId")
    suspend fun delete(downloadId: String): Int
}
