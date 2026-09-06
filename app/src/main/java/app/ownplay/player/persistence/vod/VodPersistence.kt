package app.ownplay.player.persistence.vod

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.ownplay.player.persistence.PlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

object MediaKinds {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
}

@Entity(
    tableName = "provider_vod_categories",
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
        Index(value = ["sourceId", "providerOrder"]),
    ],
)
data class ProviderVodCategoryEntity(
    @androidx.room.PrimaryKey val categoryId: String,
    val sourceId: String,
    val providerCategoryKey: String,
    val name: String,
    val parentProviderKey: String? = null,
    val providerOrder: Long,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "provider_movies",
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
        Index(value = ["sourceId", "providerStreamId"], unique = true),
        Index(value = ["sourceId", "providerCategoryKey"]),
        Index(value = ["sourceId", "providerOrder"]),
    ],
)
data class ProviderMovieEntity(
    @androidx.room.PrimaryKey val movieId: String,
    val sourceId: String,
    val providerStreamId: String,
    val providerCategoryKey: String? = null,
    val providerName: String,
    val posterRef: String? = null,
    val containerExtension: String? = null,
    val providerRating: Double? = null,
    val addedAtEpochSeconds: Long? = null,
    val providerOrder: Long,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "media_favorites",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
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
        Index(value = ["mediaKind", "addedAtEpochMillis"]),
    ],
)
data class MediaFavoriteEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val addedAtEpochMillis: Long,
)

@Entity(
    tableName = "playback_progress",
    primaryKeys = ["sourceId", "mediaKind", "contentId"],
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
        Index(value = ["mediaKind", "updatedAtEpochMillis"]),
    ],
)
data class PlaybackProgressEntity(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long? = null,
    val completed: Boolean = false,
    val updatedAtEpochMillis: Long,
)

data class VodMovieRow(
    val movieId: String,
    val sourceId: String,
    val providerStreamId: String,
    val providerCategoryKey: String?,
    val providerName: String,
    val posterRef: String?,
    val containerExtension: String?,
    val providerRating: Double?,
    val addedAtEpochSeconds: Long?,
    val providerOrder: Long,
    val isFavorite: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    val progressCompleted: Boolean?,
    val progressUpdatedAtEpochMillis: Long?,
)

@Dao
interface VodCatalogDao {
    @Query(
        """
        SELECT * FROM provider_vod_categories
        WHERE sourceId = :sourceId
        ORDER BY providerOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeCategories(sourceId: String): Flow<List<ProviderVodCategoryEntity>>

    @Query(
        """
        SELECT
            m.movieId,
            m.sourceId,
            m.providerStreamId,
            m.providerCategoryKey,
            m.providerName,
            m.posterRef,
            m.containerExtension,
            m.providerRating,
            m.addedAtEpochSeconds,
            m.providerOrder,
            CASE WHEN f.contentId IS NULL THEN 0 ELSE 1 END AS isFavorite,
            p.positionMs,
            p.durationMs,
            p.completed AS progressCompleted,
            p.updatedAtEpochMillis AS progressUpdatedAtEpochMillis
        FROM provider_movies AS m
        LEFT JOIN media_favorites AS f
            ON f.sourceId = m.sourceId
            AND f.mediaKind = 'movie'
            AND f.contentId = m.movieId
        LEFT JOIN playback_progress AS p
            ON p.sourceId = m.sourceId
            AND p.mediaKind = 'movie'
            AND p.contentId = m.movieId
        WHERE m.sourceId = :sourceId
        ORDER BY m.providerOrder ASC, m.providerName COLLATE NOCASE ASC
        """,
    )
    fun observeMovies(sourceId: String): Flow<List<VodMovieRow>>

    @Query(
        """
        SELECT
            m.movieId,
            m.sourceId,
            m.providerStreamId,
            m.providerCategoryKey,
            m.providerName,
            m.posterRef,
            m.containerExtension,
            m.providerRating,
            m.addedAtEpochSeconds,
            m.providerOrder,
            CASE WHEN f.contentId IS NULL THEN 0 ELSE 1 END AS isFavorite,
            p.positionMs,
            p.durationMs,
            p.completed AS progressCompleted,
            p.updatedAtEpochMillis AS progressUpdatedAtEpochMillis
        FROM provider_movies AS m
        JOIN playback_progress AS p
            ON p.sourceId = m.sourceId
            AND p.mediaKind = 'movie'
            AND p.contentId = m.movieId
        LEFT JOIN media_favorites AS f
            ON f.sourceId = m.sourceId
            AND f.mediaKind = 'movie'
            AND f.contentId = m.movieId
        WHERE m.sourceId = :sourceId
            AND p.positionMs > 0
            AND p.completed = 0
        ORDER BY p.updatedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(sourceId: String, limit: Int = 20): Flow<List<VodMovieRow>>

    @Query("SELECT * FROM provider_movies WHERE sourceId = :sourceId AND movieId = :movieId LIMIT 1")
    suspend fun movie(sourceId: String, movieId: String): ProviderMovieEntity?

    @Query(
        """
        SELECT * FROM playback_progress
        WHERE sourceId = :sourceId
            AND mediaKind = :mediaKind
            AND contentId = :contentId
        LIMIT 1
        """,
    )
    suspend fun progress(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<ProviderVodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovies(movies: List<ProviderMovieEntity>)

    @Query(
        "DELETE FROM provider_vod_categories " +
            "WHERE sourceId = :sourceId AND lastSeenGeneration != :generation",
    )
    suspend fun deleteStaleCategories(sourceId: String, generation: Long)

    @Query(
        "DELETE FROM provider_movies " +
            "WHERE sourceId = :sourceId AND lastSeenGeneration != :generation",
    )
    suspend fun deleteStaleMovies(sourceId: String, generation: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: MediaFavoriteEntity)

    @Query(
        "DELETE FROM media_favorites " +
            "WHERE sourceId = :sourceId AND mediaKind = :mediaKind AND contentId = :contentId",
    )
    suspend fun deleteFavorite(sourceId: String, mediaKind: String, contentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: PlaybackProgressEntity)

    @Query(
        "DELETE FROM playback_progress " +
            "WHERE sourceId = :sourceId AND mediaKind = :mediaKind AND contentId = :contentId",
    )
    suspend fun clearProgress(sourceId: String, mediaKind: String, contentId: String)

    @Transaction
    suspend fun reconcileCatalog(
        sourceId: String,
        generation: Long,
        categories: List<ProviderVodCategoryEntity>,
        movies: List<ProviderMovieEntity>,
    ) {
        if (categories.isNotEmpty()) upsertCategories(categories)
        if (movies.isNotEmpty()) upsertMovies(movies)
        deleteStaleMovies(sourceId, generation)
        deleteStaleCategories(sourceId, generation)
    }
}
