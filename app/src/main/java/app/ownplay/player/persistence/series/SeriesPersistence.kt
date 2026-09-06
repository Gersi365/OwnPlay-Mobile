package app.ownplay.player.persistence.series

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.ownplay.player.persistence.PlaylistSourceEntity
import app.ownplay.player.persistence.vod.MediaFavoriteEntity
import app.ownplay.player.persistence.vod.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

object SeriesMediaKinds {
    const val SERIES = "series"
    const val EPISODE = "episode"
}

@Entity(
    tableName = "provider_series_categories",
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
data class ProviderSeriesCategoryEntity(
    @androidx.room.PrimaryKey val categoryId: String,
    val sourceId: String,
    val providerCategoryKey: String,
    val name: String,
    val parentProviderKey: String? = null,
    val providerOrder: Long,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "provider_series",
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
        Index(value = ["sourceId", "providerSeriesId"], unique = true),
        Index(value = ["sourceId", "providerCategoryKey"]),
        Index(value = ["sourceId", "providerOrder"]),
    ],
)
data class ProviderSeriesEntity(
    @androidx.room.PrimaryKey val seriesId: String,
    val sourceId: String,
    val providerSeriesId: String,
    val providerCategoryKey: String? = null,
    val providerName: String,
    val posterRef: String? = null,
    val description: String? = null,
    val providerRating: Double? = null,
    val lastModifiedEpochSeconds: Long? = null,
    val providerOrder: Long,
    val lastSeenGeneration: Long,
)

@Entity(
    tableName = "provider_series_seasons",
    foreignKeys = [
        ForeignKey(
            entity = ProviderSeriesEntity::class,
            parentColumns = ["seriesId"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["seriesId", "seasonNumber"], unique = true),
    ],
)
data class ProviderSeriesSeasonEntity(
    @androidx.room.PrimaryKey val seasonId: String,
    val seriesId: String,
    val seasonNumber: Int,
    val name: String? = null,
    val airDate: String? = null,
    val posterRef: String? = null,
)

@Entity(
    tableName = "provider_series_episodes",
    foreignKeys = [
        ForeignKey(
            entity = ProviderSeriesEntity::class,
            parentColumns = ["seriesId"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProviderSeriesSeasonEntity::class,
            parentColumns = ["seasonId"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["seasonId"]),
        Index(value = ["seriesId", "providerEpisodeId"], unique = true),
        Index(value = ["seriesId", "seasonNumber", "episodeNumber"]),
    ],
)
data class ProviderSeriesEpisodeEntity(
    @androidx.room.PrimaryKey val episodeId: String,
    val seriesId: String,
    val seasonId: String,
    val providerEpisodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String? = null,
    val durationSeconds: Long? = null,
    val description: String? = null,
    val posterRef: String? = null,
    val providerRating: Double? = null,
    val addedAtEpochSeconds: Long? = null,
)

data class SeriesRow(
    val seriesId: String,
    val sourceId: String,
    val providerSeriesId: String,
    val providerCategoryKey: String?,
    val providerName: String,
    val posterRef: String?,
    val description: String?,
    val providerRating: Double?,
    val lastModifiedEpochSeconds: Long?,
    val providerOrder: Long,
    val isFavorite: Boolean,
)

data class EpisodeProgressRow(
    val episodeId: String,
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val providerEpisodeId: String,
    val containerExtension: String?,
    val durationSeconds: Long?,
    val posterRef: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val progressCompleted: Boolean?,
    val progressUpdatedAtEpochMillis: Long?,
)

@Dao
interface SeriesCatalogDao {
    @Query(
        """
        SELECT * FROM provider_series_categories
        WHERE sourceId = :sourceId
        ORDER BY providerOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeCategories(sourceId: String): Flow<List<ProviderSeriesCategoryEntity>>

    @Query(
        """
        SELECT
            s.seriesId,
            s.sourceId,
            s.providerSeriesId,
            s.providerCategoryKey,
            s.providerName,
            s.posterRef,
            s.description,
            s.providerRating,
            s.lastModifiedEpochSeconds,
            s.providerOrder,
            CASE WHEN f.contentId IS NULL THEN 0 ELSE 1 END AS isFavorite
        FROM provider_series AS s
        LEFT JOIN media_favorites AS f
            ON f.sourceId = s.sourceId
            AND f.mediaKind = 'series'
            AND f.contentId = s.seriesId
        WHERE s.sourceId = :sourceId
        ORDER BY s.providerOrder ASC, s.providerName COLLATE NOCASE ASC
        """,
    )
    fun observeSeries(sourceId: String): Flow<List<SeriesRow>>

    @Query(
        """
        SELECT
            e.episodeId,
            e.seriesId,
            s.providerName AS seriesTitle,
            e.seasonNumber,
            e.episodeNumber,
            e.title,
            e.providerEpisodeId,
            e.containerExtension,
            e.durationSeconds,
            COALESCE(e.posterRef, s.posterRef) AS posterRef,
            p.positionMs,
            p.durationMs,
            p.completed AS progressCompleted,
            p.updatedAtEpochMillis AS progressUpdatedAtEpochMillis
        FROM provider_series_episodes AS e
        JOIN provider_series AS s ON s.seriesId = e.seriesId
        JOIN playback_progress AS p
            ON p.sourceId = s.sourceId
            AND p.mediaKind = 'episode'
            AND p.contentId = e.episodeId
        WHERE s.sourceId = :sourceId
            AND p.positionMs > 0
            AND p.completed = 0
        ORDER BY p.updatedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(sourceId: String, limit: Int = 20): Flow<List<EpisodeProgressRow>>

    @Query("SELECT * FROM provider_series WHERE sourceId = :sourceId AND seriesId = :seriesId LIMIT 1")
    suspend fun series(sourceId: String, seriesId: String): ProviderSeriesEntity?

    @Query("SELECT * FROM provider_series_seasons WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    suspend fun seasons(seriesId: String): List<ProviderSeriesSeasonEntity>

    @Query(
        "SELECT * FROM provider_series_episodes WHERE seriesId = :seriesId " +
            "ORDER BY seasonNumber ASC, episodeNumber ASC",
    )
    suspend fun episodes(seriesId: String): List<ProviderSeriesEpisodeEntity>

    @Query(
        """
        SELECT
            e.episodeId,
            e.seriesId,
            s.providerName AS seriesTitle,
            e.seasonNumber,
            e.episodeNumber,
            e.title,
            e.providerEpisodeId,
            e.containerExtension,
            e.durationSeconds,
            COALESCE(e.posterRef, s.posterRef) AS posterRef,
            p.positionMs,
            p.durationMs,
            p.completed AS progressCompleted,
            p.updatedAtEpochMillis AS progressUpdatedAtEpochMillis
        FROM provider_series_episodes AS e
        JOIN provider_series AS s ON s.seriesId = e.seriesId
        LEFT JOIN playback_progress AS p
            ON p.sourceId = s.sourceId
            AND p.mediaKind = 'episode'
            AND p.contentId = e.episodeId
        WHERE s.sourceId = :sourceId AND e.seriesId = :seriesId
        ORDER BY e.seasonNumber ASC, e.episodeNumber ASC
        """,
    )
    suspend fun episodeRows(sourceId: String, seriesId: String): List<EpisodeProgressRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<ProviderSeriesCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: List<ProviderSeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeasons(seasons: List<ProviderSeriesSeasonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisodes(episodes: List<ProviderSeriesEpisodeEntity>)

    @Query("DELETE FROM provider_series_episodes WHERE seriesId = :seriesId")
    suspend fun deleteEpisodes(seriesId: String)

    @Query("DELETE FROM provider_series_seasons WHERE seriesId = :seriesId")
    suspend fun deleteSeasons(seriesId: String)

    @Query(
        "DELETE FROM provider_series_categories " +
            "WHERE sourceId = :sourceId AND lastSeenGeneration != :generation",
    )
    suspend fun deleteStaleCategories(sourceId: String, generation: Long)

    @Query(
        "DELETE FROM provider_series " +
            "WHERE sourceId = :sourceId AND lastSeenGeneration != :generation",
    )
    suspend fun deleteStaleSeries(sourceId: String, generation: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: MediaFavoriteEntity)

    @Query(
        "DELETE FROM media_favorites " +
            "WHERE sourceId = :sourceId AND mediaKind = :mediaKind AND contentId = :contentId",
    )
    suspend fun deleteFavorite(sourceId: String, mediaKind: String, contentId: String)

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
        categories: List<ProviderSeriesCategoryEntity>,
        series: List<ProviderSeriesEntity>,
    ) {
        if (categories.isNotEmpty()) upsertCategories(categories)
        if (series.isNotEmpty()) upsertSeries(series)
        deleteStaleSeries(sourceId, generation)
        deleteStaleCategories(sourceId, generation)
    }

    @Transaction
    suspend fun replaceDetails(
        seriesId: String,
        seasons: List<ProviderSeriesSeasonEntity>,
        episodes: List<ProviderSeriesEpisodeEntity>,
    ) {
        deleteEpisodes(seriesId)
        deleteSeasons(seriesId)
        if (seasons.isNotEmpty()) upsertSeasons(seasons)
        if (episodes.isNotEmpty()) upsertEpisodes(episodes)
    }
}
