package app.ownplay.player.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.persistence.download.MediaDownloadEntity
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackRequest
import app.ownplay.player.playback.ResolvedPlaybackLocator
import app.ownplay.player.playback.ResolvedPlaybackOrigin
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class OfflineDownloadSpec(
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val providerStreamId: Int,
    val title: String,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String? = null,
    val containerExtension: String? = null,
)

data class OfflineDownload(
    val downloadId: String,
    val sourceId: String,
    val mediaKind: String,
    val contentId: String,
    val title: String,
    val seriesTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val posterUrl: String?,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val failureReason: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val savedToDownloads: Boolean = false,
) {
    val completed: Boolean
        get() = state == DownloadStates.COMPLETED

    val active: Boolean
        get() = state == DownloadStates.QUEUED || state == DownloadStates.DOWNLOADING

    val paused: Boolean
        get() = state == DownloadStates.PAUSED

    val progressFraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

class OfflineDownloadRepository(
    context: Context,
    private val database: OwnPlayDatabase,
) {
    private val applicationContext = context.applicationContext
    private val dao = database.mediaDownloadDao()
    private val workManager = WorkManager.getInstance(applicationContext)

    fun observeAll(): Flow<List<OfflineDownload>> =
        dao.observeAll().map { rows -> rows.map(::mapDownload) }

    suspend fun reconcilePendingWork(): Int {
        val pending = dao.needingWork()
        pending.forEach { row ->
            enqueueWork(
                downloadId = row.downloadId,
                existingWorkPolicy = ExistingWorkPolicy.KEEP,
            )
        }
        return pending.size
    }

    suspend fun reconcileCompletedFiles(): Int {
        var invalidCount = 0
        dao.completed().forEach { row ->
            if (OfflineDownloadFileIntegrity.verifiedBytes(applicationContext, row) == null) {
                markCompletedFileFailed(row)
                invalidCount += 1
            }
        }
        return invalidCount
    }

    suspend fun enqueue(spec: OfflineDownloadSpec): String {
        require(spec.sourceId.isNotBlank()) { "sourceId is required" }
        require(spec.contentId.isNotBlank()) { "contentId is required" }
        require(spec.providerStreamId > 0) { "providerStreamId must be positive" }
        require(spec.title.isNotBlank()) { "title is required" }
        require(
            spec.mediaKind == DownloadMediaKinds.MOVIE ||
                spec.mediaKind == DownloadMediaKinds.SERIES_EPISODE,
        ) { "Unsupported download media kind" }

        val normalizedTitle = spec.title.trim()
        val normalizedSeriesTitle = spec.seriesTitle?.trim()?.takeIf(String::isNotBlank)
        val existing = dao.getForContent(spec.sourceId, spec.mediaKind, spec.contentId)
        if (existing != null) {
            if (
                existing.state == DownloadStates.COMPLETED &&
                OfflineDownloadFileIntegrity.verifiedBytes(applicationContext, existing) != null
            ) {
                return existing.downloadId
            }
            when (existing.state) {
                DownloadStates.DOWNLOADING,
                DownloadStates.QUEUED,
                -> {
                    enqueueWork(
                        downloadId = existing.downloadId,
                        existingWorkPolicy = ExistingWorkPolicy.KEEP,
                    )
                    return existing.downloadId
                }
                DownloadStates.PAUSED -> return existing.downloadId
            }
            val existingLocation = existing.localRelativePath
                ?.takeIf { OfflineDownloadStorage.locationExists(applicationContext, it) }
            val existingBytes = transferBytes(existing.downloadId, existingLocation)
            dao.upsert(
                existing.copy(
                    providerStreamId = spec.providerStreamId,
                    title = normalizedTitle,
                    seriesTitle = normalizedSeriesTitle,
                    seasonNumber = spec.seasonNumber,
                    episodeNumber = spec.episodeNumber,
                    posterUrl = spec.posterUrl,
                    containerExtension = spec.containerExtension,
                    state = DownloadStates.QUEUED,
                    bytesDownloaded = existingBytes,
                    totalBytes = null,
                    localRelativePath = existingLocation,
                    failureReason = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            enqueueWork(existing.downloadId)
            return existing.downloadId
        }

        val now = System.currentTimeMillis()
        val downloadId = UUID.randomUUID().toString()
        dao.upsert(
            MediaDownloadEntity(
                downloadId = downloadId,
                sourceId = spec.sourceId,
                mediaKind = spec.mediaKind,
                contentId = spec.contentId,
                providerStreamId = spec.providerStreamId,
                title = normalizedTitle,
                seriesTitle = normalizedSeriesTitle,
                seasonNumber = spec.seasonNumber,
                episodeNumber = spec.episodeNumber,
                posterUrl = spec.posterUrl,
                containerExtension = spec.containerExtension,
                state = DownloadStates.QUEUED,
                bytesDownloaded = 0L,
                totalBytes = null,
                localRelativePath = null,
                failureReason = null,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        enqueueWork(downloadId)
        return downloadId
    }

    suspend fun pause(downloadId: String) {
        val existing = dao.getById(downloadId) ?: return
        if (existing.state != DownloadStates.QUEUED && existing.state != DownloadStates.DOWNLOADING) {
            return
        }
        val paused = dao.updateActiveTransfer(
            downloadId = downloadId,
            state = DownloadStates.PAUSED,
            bytesDownloaded = transferBytes(downloadId, existing.localRelativePath)
                .takeIf { it > 0L }
                ?: existing.bytesDownloaded,
            totalBytes = existing.totalBytes,
            localRelativePath = existing.localRelativePath,
            failureReason = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        if (paused > 0) {
            workManager.cancelUniqueWork(workName(downloadId))
        }
    }

    suspend fun resume(downloadId: String) {
        val existing = dao.getById(downloadId) ?: return
        if (existing.state != DownloadStates.PAUSED) return
        dao.updateTransfer(
            downloadId = downloadId,
            state = DownloadStates.QUEUED,
            bytesDownloaded = transferBytes(downloadId, existing.localRelativePath)
                .takeIf { it > 0L }
                ?: existing.bytesDownloaded,
            totalBytes = existing.totalBytes,
            localRelativePath = existing.localRelativePath,
            failureReason = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        enqueueWork(downloadId)
    }

    suspend fun retry(downloadId: String) {
        val existing = dao.getById(downloadId) ?: return
        if (
            existing.state == DownloadStates.COMPLETED &&
            OfflineDownloadFileIntegrity.verifiedBytes(applicationContext, existing) != null
        ) {
            return
        }
        val existingLocation = existing.localRelativePath
            ?.takeIf { OfflineDownloadStorage.locationExists(applicationContext, it) }
        dao.upsert(
            existing.copy(
                state = DownloadStates.QUEUED,
                bytesDownloaded = transferBytes(downloadId, existingLocation),
                totalBytes = null,
                localRelativePath = existingLocation,
                failureReason = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        enqueueWork(downloadId)
    }

    suspend fun remove(downloadId: String) {
        workManager.cancelUniqueWork(workName(downloadId))
        val existing = dao.getById(downloadId)
        OfflineDownloadStorage.deleteLocation(applicationContext, existing?.localRelativePath)
        OfflineDownloadStorage.partialFile(applicationContext, downloadId).delete()
        dao.delete(downloadId)
        if (existing != null) {
            OfflineDownloadStorage.privateFinalFile(
                applicationContext,
                downloadId,
                existing.containerExtension ?: "mp4",
            ).delete()
        }
    }

    suspend fun localPlaybackLocator(request: PlaybackRequest): ResolvedPlaybackLocator? {
        val mediaKind = when (request.mediaKind) {
            PlaybackMediaKind.LIVE -> return null
            PlaybackMediaKind.MOVIE -> DownloadMediaKinds.MOVIE
            PlaybackMediaKind.SERIES_EPISODE -> DownloadMediaKinds.SERIES_EPISODE
        }
        val row = dao.getForContent(
            sourceId = request.sourceId,
            mediaKind = mediaKind,
            contentId = request.channelId,
        ) ?: return null
        if (row.state != DownloadStates.COMPLETED) return null
        if (OfflineDownloadFileIntegrity.verifiedBytes(applicationContext, row) == null) {
            markCompletedFileFailed(row)
            return null
        }
        val playbackUri = OfflineDownloadStorage.playbackUri(
            applicationContext,
            row.localRelativePath,
        )
        if (playbackUri == null) {
            markCompletedFileFailed(row)
            return null
        }
        return ResolvedPlaybackLocator(
            value = playbackUri,
            origin = ResolvedPlaybackOrigin.LOCAL_DOWNLOAD,
        )
    }

    private suspend fun markCompletedFileFailed(row: MediaDownloadEntity) {
        val locationExists = OfflineDownloadStorage.locationExists(
            applicationContext,
            row.localRelativePath,
        )
        val actualBytes = if (locationExists) {
            OfflineDownloadStorage.locationSize(applicationContext, row.localRelativePath) ?: 0L
        } else {
            0L
        }
        dao.updateTransfer(
            downloadId = row.downloadId,
            state = DownloadStates.FAILED,
            bytesDownloaded = actualBytes,
            totalBytes = row.totalBytes,
            localRelativePath = row.localRelativePath.takeIf { locationExists },
            failureReason = OfflineDownloadFileIntegrity.failureReason(applicationContext, row),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun enqueueWork(
        downloadId: String,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInputData(workDataOf(OfflineDownloadWorker.KEY_DOWNLOAD_ID to downloadId))
            .build()
        workManager.enqueueUniqueWork(
            workName(downloadId),
            existingWorkPolicy,
            request,
        )
    }

    private fun transferBytes(downloadId: String, location: String?): Long {
        if (OfflineDownloadStorage.isPublicDownloadsLocation(location)) {
            return OfflineDownloadStorage.locationSize(applicationContext, location) ?: 0L
        }
        return OfflineDownloadStorage.partialFile(applicationContext, downloadId)
            .takeIf(File::isFile)
            ?.length()
            ?: 0L
    }

    private fun mapDownload(row: MediaDownloadEntity): OfflineDownload = OfflineDownload(
        downloadId = row.downloadId,
        sourceId = row.sourceId,
        mediaKind = row.mediaKind,
        contentId = row.contentId,
        title = row.title,
        seriesTitle = row.seriesTitle,
        seasonNumber = row.seasonNumber,
        episodeNumber = row.episodeNumber,
        posterUrl = row.posterUrl,
        state = row.state,
        bytesDownloaded = row.bytesDownloaded,
        totalBytes = row.totalBytes,
        failureReason = row.failureReason,
        createdAtEpochMillis = row.createdAtEpochMillis,
        updatedAtEpochMillis = row.updatedAtEpochMillis,
        savedToDownloads = OfflineDownloadStorage.isPublicDownloadsLocation(row.localRelativePath),
    )

    companion object {
        private const val RETRY_BACKOFF_SECONDS = 30L

        fun workName(downloadId: String): String = "ownplay-offline-download-$downloadId"
    }
}
