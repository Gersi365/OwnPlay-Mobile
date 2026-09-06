package app.ownplay.player.persistence.download

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.ownplay.player.persistence.PlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

object DownloadMediaKinds {
    const val MOVIE = "MOVIE"
    const val SERIES_EPISODE = "SERIES_EPISODE"
}

object DownloadStates {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Entity(
    tableName = "media_downloads",
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
        Index(value = ["state", "updatedAtEpochMillis"]),
        Index(value = ["sourceId", "mediaKind", "contentId"], unique = true),
    ],
)
data class MediaDownloadEntity(
    @PrimaryKey
    val downloadId: String,
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val providerStreamId: Int,
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

@Dao
interface MediaDownloadDao {
    @Query(
        """
        SELECT * FROM media_downloads
        ORDER BY
            CASE state
                WHEN 'DOWNLOADING' THEN 0
                WHEN 'QUEUED' THEN 1
                WHEN 'PAUSED' THEN 2
                WHEN 'FAILED' THEN 3
                ELSE 4
            END,
            createdAtEpochMillis DESC,
            downloadId ASC
        """,
    )
    fun observeAll(): Flow<List<MediaDownloadEntity>>

    @Query("SELECT * FROM media_downloads WHERE state = 'COMPLETED'")
    suspend fun completed(): List<MediaDownloadEntity>

    @Query("SELECT * FROM media_downloads WHERE state IN ('QUEUED', 'DOWNLOADING')")
    suspend fun needingWork(): List<MediaDownloadEntity>

    @Query("SELECT * FROM media_downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getById(downloadId: String): MediaDownloadEntity?

    @Query(
        """
        SELECT * FROM media_downloads
        WHERE sourceId = :sourceId AND mediaKind = :mediaKind AND contentId = :contentId
        LIMIT 1
        """,
    )
    suspend fun getForContent(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): MediaDownloadEntity?

    @Query(
        """
        SELECT * FROM media_downloads
        WHERE sourceId = :sourceId AND mediaKind = :mediaKind AND contentId = :contentId
        LIMIT 1
        """,
    )
    fun observeForContent(
        sourceId: String,
        mediaKind: String,
        contentId: String,
    ): Flow<MediaDownloadEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaDownloadEntity)

    @Query(
        """
        UPDATE media_downloads
        SET state = :state,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            localRelativePath = :localRelativePath,
            failureReason = :failureReason,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE downloadId = :downloadId
        """,
    )
    suspend fun updateTransfer(
        downloadId: String,
        state: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        localRelativePath: String?,
        failureReason: String?,
        updatedAtEpochMillis: Long,
    )

    @Query(
        """
        UPDATE media_downloads
        SET state = :state,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            localRelativePath = :localRelativePath,
            failureReason = :failureReason,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE downloadId = :downloadId
          AND state IN ('QUEUED', 'DOWNLOADING')
        """,
    )
    suspend fun updateActiveTransfer(
        downloadId: String,
        state: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        localRelativePath: String?,
        failureReason: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM media_downloads WHERE downloadId = :downloadId")
    suspend fun delete(downloadId: String)
}
