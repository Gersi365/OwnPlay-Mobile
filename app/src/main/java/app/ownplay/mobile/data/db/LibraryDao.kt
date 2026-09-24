package app.ownplay.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class LibraryMovieProgressRow(
    val contentId: String,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

data class LibraryEpisodeProgressRow(
    val contentId: String,
    val seriesId: String,
    val title: String,
    val seriesTitle: String,
    val posterUrl: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

data class LibraryDownloadEpisodeMetadataRow(
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT * FROM provider_categories
        WHERE sourceId = :sourceId
          AND kind = 'MOVIE'
          AND available = 1
        ORDER BY providerOrder ASC, categoryKey ASC
        """,
    )
    fun observeMovieCategories(sourceId: String): Flow<List<ProviderCategoryEntity>>

    @Query(
        """
        SELECT * FROM provider_categories
        WHERE sourceId = :sourceId
          AND kind = 'SERIES'
          AND available = 1
        ORDER BY providerOrder ASC, categoryKey ASC
        """,
    )
    fun observeSeriesCategories(sourceId: String): Flow<List<ProviderCategoryEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId
          AND available = 1
        ORDER BY providerOrder ASC, movieId ASC
        """,
    )
    fun observeMovies(sourceId: String): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId
          AND available = 1
        ORDER BY providerOrder ASC, seriesId ASC
        """,
    )
    fun observeSeries(sourceId: String): Flow<List<SeriesEntity>>

    @Query(
        """
        SELECT * FROM media_favorites
        WHERE sourceId = :sourceId
        ORDER BY addedAt ASC, mediaKind ASC, contentId ASC
        """,
    )
    fun observeFavorites(sourceId: String): Flow<List<MediaFavoriteEntity>>

    @Query(
        """
        SELECT * FROM downloads
        WHERE sourceId = :sourceId
          AND state = 'COMPLETED'
          AND localReference IS NOT NULL
          AND bytesDownloaded > 0
          AND mediaKind IN ('MOVIE', 'EPISODE')
        ORDER BY updatedAt DESC, downloadId ASC
        """,
    )
    fun observeCompletedDownloads(sourceId: String): Flow<List<DownloadEntity>>

    @Query(
        """
        SELECT
            p.contentId AS contentId,
            m.name AS title,
            m.posterUrl AS posterUrl,
            p.positionMs AS positionMs,
            p.durationMs AS durationMs,
            p.updatedAt AS updatedAt
        FROM playback_progress p
        INNER JOIN movies m
            ON m.movieId = p.contentId
           AND m.sourceId = p.sourceId
        WHERE p.sourceId = :sourceId
          AND p.mediaKind = 'MOVIE'
          AND p.completed = 0
          AND p.positionMs > 0
          AND p.durationMs > 0
          AND m.available = 1
        ORDER BY p.updatedAt DESC, p.contentId ASC
        """,
    )
    fun observeMovieContinueWatching(sourceId: String): Flow<List<LibraryMovieProgressRow>>

    @Query(
        """
        SELECT
            p.contentId AS contentId,
            e.seriesId AS seriesId,
            e.title AS title,
            s.name AS seriesTitle,
            s.posterUrl AS posterUrl,
            e.seasonNumber AS seasonNumber,
            e.episodeNumber AS episodeNumber,
            p.positionMs AS positionMs,
            p.durationMs AS durationMs,
            p.updatedAt AS updatedAt
        FROM playback_progress p
        INNER JOIN episodes e ON e.episodeId = p.contentId
        INNER JOIN series s
            ON s.seriesId = e.seriesId
           AND s.sourceId = p.sourceId
        WHERE p.sourceId = :sourceId
          AND p.mediaKind = 'EPISODE'
          AND p.completed = 0
          AND p.positionMs > 0
          AND p.durationMs > 0
          AND s.available = 1
          AND e.available = 1
        ORDER BY p.updatedAt DESC, p.contentId ASC
        """,
    )
    fun observeEpisodeContinueWatching(sourceId: String): Flow<List<LibraryEpisodeProgressRow>>

    @Query(
        """
        SELECT * FROM playback_progress
        WHERE sourceId = :sourceId
          AND mediaKind = :mediaKind
          AND contentId = :contentId
        LIMIT 1
        """,
    )
    suspend fun getPlaybackProgress(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): PlaybackProgressEntity?

    @Query(
        """
        SELECT * FROM playback_progress
        WHERE sourceId = :sourceId
          AND mediaKind = :mediaKind
        ORDER BY updatedAt DESC, contentId ASC
        """,
    )
    suspend fun getPlaybackProgressForKind(
        sourceId: String,
        mediaKind: String,
    ): List<PlaybackProgressEntity>

    @Upsert
    suspend fun upsertPlaybackProgress(entity: PlaybackProgressEntity)

    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId
          AND movieId = :movieId
          AND available = 1
        LIMIT 1
        """,
    )
    fun observeAvailableMovie(
        sourceId: String,
        movieId: String,
    ): Flow<MovieEntity?>

    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId
          AND seriesId = :seriesId
          AND available = 1
        LIMIT 1
        """,
    )
    fun observeAvailableSeries(
        sourceId: String,
        seriesId: String,
    ): Flow<SeriesEntity?>

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN series s ON s.seriesId = e.seriesId
        WHERE s.sourceId = :sourceId
          AND s.seriesId = :seriesId
          AND s.available = 1
          AND e.available = 1
        ORDER BY e.seasonNumber ASC, e.episodeNumber ASC, e.episodeId ASC
        """,
    )
    fun observeAvailableEpisodes(
        sourceId: String,
        seriesId: String,
    ): Flow<List<EpisodeEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId
          AND movieId = :movieId
          AND available = 1
        LIMIT 1
        """,
    )
    suspend fun getAvailableMovie(
        sourceId: String,
        movieId: String,
    ): MovieEntity?

    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId
          AND seriesId = :seriesId
          AND available = 1
        LIMIT 1
        """,
    )
    suspend fun getAvailableSeries(
        sourceId: String,
        seriesId: String,
    ): SeriesEntity?

    @Query(
        """
        SELECT e.* FROM episodes e
        INNER JOIN series s ON s.seriesId = e.seriesId
        WHERE s.sourceId = :sourceId
          AND e.episodeId = :episodeId
          AND s.available = 1
          AND e.available = 1
        LIMIT 1
        """,
    )
    suspend fun getAvailableEpisode(
        sourceId: String,
        episodeId: String,
    ): EpisodeEntity?

    @Query(
        """
        SELECT
            e.seriesId AS seriesId,
            s.name AS seriesTitle,
            e.seasonNumber AS seasonNumber,
            e.episodeNumber AS episodeNumber
        FROM episodes e
        INNER JOIN series s ON s.seriesId = e.seriesId
        WHERE s.sourceId = :sourceId
          AND e.episodeId = :episodeId
        LIMIT 1
        """,
    )
    suspend fun getDownloadEpisodeMetadata(
        sourceId: String,
        episodeId: String,
    ): LibraryDownloadEpisodeMetadataRow?

    @Query(
        """
        SELECT * FROM episodes
        WHERE seriesId = :seriesId
        ORDER BY seasonNumber ASC, episodeNumber ASC, episodeId ASC
        """,
    )
    suspend fun getEpisodesForSeries(seriesId: String): List<EpisodeEntity>

    @Upsert
    suspend fun upsertEpisodes(rows: List<EpisodeEntity>)

    @Transaction
    suspend fun reconcileSeriesEpisodes(
        seriesId: String,
        currentRows: List<EpisodeEntity>,
    ) {
        val currentIds = currentRows.mapTo(mutableSetOf(), EpisodeEntity::episodeId)
        val staleRows = getEpisodesForSeries(seriesId)
            .asSequence()
            .filter { it.episodeId !in currentIds && it.available }
            .map { it.copy(available = false) }
            .toList()
        val rows = currentRows + staleRows
        if (rows.isNotEmpty()) {
            upsertEpisodes(rows)
        }
    }

    @Upsert
    suspend fun upsertFavorite(entity: MediaFavoriteEntity)

    @Query(
        """
        DELETE FROM media_favorites
        WHERE sourceId = :sourceId
          AND mediaKind = :mediaKind
          AND contentId = :contentId
        """,
    )
    suspend fun deleteFavorite(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): Int
}
