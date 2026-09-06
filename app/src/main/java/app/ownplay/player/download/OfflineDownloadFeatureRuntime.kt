package app.ownplay.player.download

import android.content.Context
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.persistence.download.MediaDownloadEntity
import app.ownplay.player.persistence.vod.MediaKinds
import app.ownplay.player.persistence.vod.PlaybackProgressEntity
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackProgressPolicy
import app.ownplay.player.playback.PlaybackRequest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

data class OfflinePlaybackProgress(
    val positionMs: Long,
    val durationMs: Long?,
    val completed: Boolean,
)

class OfflineDownloadFeatureRuntime(
    context: Context,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val database = sharedDatabase(applicationContext)
    private val repository = OfflineDownloadRepository(
        context = applicationContext,
        database = database,
    )

    fun observeAll(): Flow<List<OfflineDownload>> = repository.observeAll()
        .onStart {
            try {
                reconcileCompletedFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            try {
                repository.reconcilePendingWork()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
        }

    suspend fun enqueue(spec: OfflineDownloadSpec): String = repository.enqueue(spec)

    suspend fun pause(downloadId: String) = repository.pause(downloadId)

    suspend fun resume(downloadId: String) = repository.resume(downloadId)

    suspend fun retry(downloadId: String) = repository.retry(downloadId)

    suspend fun remove(downloadId: String) = repository.remove(downloadId)

    suspend fun reconcilePendingWork(): Int = repository.reconcilePendingWork()

    suspend fun reconcileCompletedFiles(): Int = repository.reconcileCompletedFiles()

    suspend fun playbackRequest(downloadId: String): PlaybackRequest? {
        val row = database.mediaDownloadDao().getById(downloadId) ?: return null
        if (row.state != DownloadStates.COMPLETED) return null

        val request = when (row.mediaKind) {
            DownloadMediaKinds.MOVIE -> PlaybackRequest(
                sourceId = row.sourceId,
                channelId = row.contentId,
                mediaKind = PlaybackMediaKind.MOVIE,
            )

            DownloadMediaKinds.SERIES_EPISODE -> PlaybackRequest(
                sourceId = row.sourceId,
                channelId = row.contentId,
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = row.providerStreamId,
                containerExtension = row.containerExtension,
            )

            else -> return null
        }

        return if (repository.localPlaybackLocator(request) != null) request else null
    }

    suspend fun playbackProgress(downloadId: String): OfflinePlaybackProgress? {
        val row = database.mediaDownloadDao().getById(downloadId) ?: return null
        val mediaKind = row.progressMediaKind() ?: return null
        return database.vodCatalogDao()
            .progress(row.sourceId, mediaKind, row.contentId)
            ?.let { progress ->
                OfflinePlaybackProgress(
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    completed = progress.completed,
                )
            }
    }

    suspend fun savePlaybackProgress(
        request: PlaybackRequest,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        val downloadMediaKind = when (request.mediaKind) {
            PlaybackMediaKind.MOVIE -> DownloadMediaKinds.MOVIE
            PlaybackMediaKind.SERIES_EPISODE -> DownloadMediaKinds.SERIES_EPISODE
            else -> return false
        }
        val row = database.mediaDownloadDao().getForContent(
            sourceId = request.sourceId,
            mediaKind = downloadMediaKind,
            contentId = request.channelId,
        ) ?: return false
        if (row.state != DownloadStates.COMPLETED) return false
        return savePlaybackProgress(
            downloadId = row.downloadId,
            positionMs = positionMs,
            durationMs = durationMs,
        )
    }

    suspend fun savePlaybackProgress(
        downloadId: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean {
        return try {
            val row = database.mediaDownloadDao().getById(downloadId) ?: return false
            val mediaKind = row.progressMediaKind() ?: return false
            val existing = database.vodCatalogDao().progress(
                row.sourceId,
                mediaKind,
                row.contentId,
            )
            val positionForSave = PlaybackProgressPolicy.positionForSave(
                positionMs = positionMs,
                fallbackPositionMs = existing?.positionMs,
            )
            val normalized = PlaybackProgressPolicy.normalize(
                positionMs = positionForSave,
                durationMs = durationMs,
                fallbackDurationMs = existing?.durationMs,
            )
            database.vodCatalogDao().upsertProgress(
                PlaybackProgressEntity(
                    sourceId = row.sourceId,
                    mediaKind = mediaKind,
                    contentId = row.contentId,
                    positionMs = normalized.positionMs,
                    durationMs = normalized.durationMs,
                    completed = normalized.completed,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        // Screen-scoped runtimes share a process-scoped Room instance. Closing the database
        // while another route is collecting download progress can race with navigation and
        // active WorkManager writes, so the shared database intentionally lives for the process.
    }

    private fun MediaDownloadEntity.progressMediaKind(): String? =
        when (mediaKind) {
            DownloadMediaKinds.MOVIE -> MediaKinds.MOVIE
            DownloadMediaKinds.SERIES_EPISODE -> MediaKinds.EPISODE
            else -> null
        }

    private companion object {
        @Volatile
        private var processDatabase: OwnPlayDatabase? = null

        fun sharedDatabase(context: Context): OwnPlayDatabase =
            processDatabase ?: synchronized(this) {
                processDatabase ?: OwnPlayDatabase.create(context).also { database ->
                    processDatabase = database
                }
            }
    }
}
