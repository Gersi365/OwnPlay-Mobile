package app.ownplay.player.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.download.DownloadStates
import app.ownplay.player.persistence.download.MediaDownloadDao
import app.ownplay.player.persistence.download.MediaDownloadEntity
import app.ownplay.player.persistence.secure.AndroidKeystoreSensitiveValueStore
import app.ownplay.player.source.credential.AndroidKeystoreCredentialStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

class OfflineDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val database = OwnPlayDatabase.create(applicationContext)
        val dao = database.mediaDownloadDao()
        var activePublicDestination: String? = null
        try {
            val initialRow = dao.getById(downloadId) ?: return Result.success()
            if (initialRow.state == DownloadStates.PAUSED) {
                return Result.success()
            }
            if (initialRow.state == DownloadStates.COMPLETED) {
                if (OfflineDownloadFileIntegrity.verifiedBytes(applicationContext, initialRow) != null) {
                    return Result.success()
                }
                val retainedLocation = initialRow.localRelativePath
                    ?.takeIf { OfflineDownloadStorage.locationExists(applicationContext, it) }
                val actualBytes = retainedLocation
                    ?.let { OfflineDownloadStorage.locationSize(applicationContext, it) }
                    ?: 0L
                markFailed(
                    dao = dao,
                    row = initialRow,
                    reason = OfflineDownloadFileIntegrity.failureReason(applicationContext, initialRow),
                    bytesDownloaded = actualBytes,
                    localLocation = retainedLocation,
                )
                return Result.failure()
            }
            if (recoverFinalizedDownload(initialRow, dao)) {
                return Result.success()
            }

            setForeground(createForegroundInfo(initialRow, initialRow.bytesDownloaded, initialRow.totalBytes))
            val resolver = XtreamDownloadLocatorResolver(
                database = database,
                sensitiveValueStore = AndroidKeystoreSensitiveValueStore(applicationContext),
                credentialStore = AndroidKeystoreCredentialStore(applicationContext),
            )
            val locator = when (val resolved = resolver.resolve(initialRow)) {
                is DownloadLocatorResult.Success -> resolved.locator
                is DownloadLocatorResult.Failure -> {
                    markFailed(
                        dao = dao,
                        row = initialRow,
                        reason = resolved.reason,
                        localLocation = initialRow.localRelativePath,
                    )
                    return Result.failure()
                }
            }

            val partFile = OfflineDownloadStorage.partialFile(applicationContext, downloadId)
            val legacyPartialExists = partFile.isFile && partFile.length() > 0L
            val legacyPrivateLocation = initialRow.localRelativePath?.let { location ->
                !OfflineDownloadStorage.isPublicDownloadsLocation(location)
            } == true
            val usePublicDownloads =
                OfflineDownloadStorage.supportsPublicDownloads() &&
                    !legacyPartialExists &&
                    !legacyPrivateLocation
            val destinationLocation = if (usePublicDownloads) {
                initialRow.localRelativePath
                    ?.takeIf(OfflineDownloadStorage::isPublicDownloadsLocation)
                    ?.takeIf {
                        OfflineDownloadStorage.locationExists(applicationContext, it)
                    }
                    ?: OfflineDownloadStorage.createPublicDownloadsDestination(
                        applicationContext,
                        initialRow,
                    )
            } else {
                null
            }
            if (usePublicDownloads) {
                activePublicDestination = destinationLocation
            }
            val existingBytes = if (usePublicDownloads) {
                OfflineDownloadStorage.locationSize(applicationContext, destinationLocation) ?: 0L
            } else {
                partFile.takeIf(File::isFile)?.length() ?: 0L
            }

            val markedDownloading = dao.updateActiveTransfer(
                downloadId = downloadId,
                state = DownloadStates.DOWNLOADING,
                bytesDownloaded = existingBytes,
                totalBytes = null,
                localRelativePath = destinationLocation,
                failureReason = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            if (markedDownloading == 0) {
                val currentRow = dao.getById(downloadId)
                if (
                    activePublicDestination != null &&
                    currentRow?.localRelativePath != activePublicDestination
                ) {
                    OfflineDownloadStorage.deleteLocation(applicationContext, activePublicDestination)
                }
                return Result.success()
            }

            val requestBuilder = Request.Builder().url(locator.value)
            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use { opened ->
                if (!opened.isSuccessful) {
                    val reason = "Provider returned HTTP ${opened.code}"
                    return when (
                        OfflineDownloadRetryPolicy.forHttpStatus(
                            statusCode = opened.code,
                            hasPartialContent = existingBytes > 0L,
                        )
                    ) {
                        OfflineDownloadFailureDisposition.RETRY -> {
                            if (
                                markQueuedForRetry(
                                    dao = dao,
                                    row = initialRow,
                                    reason = reason,
                                    bytesDownloaded = existingBytes,
                                    localLocation = destinationLocation,
                                )
                            ) {
                                Result.retry()
                            } else {
                                Result.success()
                            }
                        }
                        OfflineDownloadFailureDisposition.RESTART -> {
                            if (usePublicDownloads) {
                                OfflineDownloadStorage.openPublicOutput(
                                    context = applicationContext,
                                    location = requireNotNull(destinationLocation),
                                    append = false,
                                    startBytes = 0L,
                                ).use { output -> output.flush() }
                            } else if (partFile.exists() && !partFile.delete()) {
                                markFailed(
                                    dao = dao,
                                    row = initialRow,
                                    reason = "Could not reset the partial download after HTTP 416",
                                    bytesDownloaded = existingBytes,
                                    localLocation = destinationLocation,
                                )
                                return Result.failure()
                            }
                            if (
                                markQueuedForRetry(
                                    dao = dao,
                                    row = initialRow,
                                    reason = "Provider rejected resume. Restarting from the beginning.",
                                    bytesDownloaded = 0L,
                                    totalBytes = null,
                                    localLocation = destinationLocation,
                                )
                            ) {
                                Result.retry()
                            } else {
                                Result.success()
                            }
                        }
                        OfflineDownloadFailureDisposition.FAIL -> {
                            markFailed(
                                dao = dao,
                                row = initialRow,
                                reason = reason,
                                bytesDownloaded = existingBytes,
                                localLocation = destinationLocation,
                            )
                            Result.failure()
                        }
                    }
                }

                val body = opened.body
                val bodyLength = body.contentLength().takeIf { it >= 0L }
                val responsePlan = OfflineDownloadResponsePolicy.plan(
                    statusCode = opened.code,
                    existingBytes = existingBytes,
                    contentRange = opened.header("Content-Range"),
                    contentLength = bodyLength,
                )
                when (responsePlan.disposition) {
                    OfflineDownloadWriteDisposition.RESTART -> {
                        if (usePublicDownloads) {
                            OfflineDownloadStorage.openPublicOutput(
                                context = applicationContext,
                                location = requireNotNull(destinationLocation),
                                append = false,
                                startBytes = 0L,
                            ).use { output -> output.flush() }
                        } else if (partFile.exists() && !partFile.delete()) {
                            markFailed(
                                dao = dao,
                                row = initialRow,
                                reason = "Could not reset the partial download after an invalid resume response",
                                bytesDownloaded = existingBytes,
                                localLocation = destinationLocation,
                            )
                            return Result.failure()
                        }
                        return if (
                            markQueuedForRetry(
                                dao = dao,
                                row = initialRow,
                                reason = "Provider returned an incompatible resume range. Restarting from the beginning.",
                                bytesDownloaded = 0L,
                                totalBytes = null,
                                localLocation = destinationLocation,
                            )
                        ) {
                            Result.retry()
                        } else {
                            Result.success()
                        }
                    }
                    OfflineDownloadWriteDisposition.FAIL -> {
                        markFailed(
                            dao = dao,
                            row = initialRow,
                            reason = "Provider returned an invalid or empty media response",
                            bytesDownloaded = existingBytes,
                            totalBytes = responsePlan.expectedTotalBytes,
                            localLocation = destinationLocation,
                        )
                        return Result.failure()
                    }
                    OfflineDownloadWriteDisposition.WRITE_FROM_ZERO,
                    OfflineDownloadWriteDisposition.APPEND,
                    -> Unit
                }

                val append = responsePlan.disposition == OfflineDownloadWriteDisposition.APPEND
                val startBytes = if (append) existingBytes else 0L
                if (!usePublicDownloads && !append && partFile.exists()) {
                    partFile.delete()
                }
                val totalBytes = responsePlan.expectedTotalBytes
                if (bodyLength != null) {
                    val usableSpace = OfflineDownloadStorage.usableSpaceBytes(
                        context = applicationContext,
                        destinationLocation = destinationLocation,
                    )
                    if (
                        shouldFailOfflineDownloadPreflight(
                            usableSpaceBytes = usableSpace,
                            requiredBytes = bodyLength,
                        )
                    ) {
                        markFailed(
                            dao = dao,
                            row = initialRow,
                            reason = "Not enough free storage for this download. Free up space and retry.",
                            bytesDownloaded = startBytes,
                            totalBytes = totalBytes,
                            localLocation = destinationLocation,
                        )
                        return Result.failure()
                    }
                }
                var downloaded = startBytes
                var lastReportedBytes = downloaded
                var lastReportedAt = System.currentTimeMillis()

                val output = if (usePublicDownloads) {
                    OfflineDownloadStorage.openPublicOutput(
                        context = applicationContext,
                        location = requireNotNull(destinationLocation),
                        append = append,
                        startBytes = startBytes,
                    )
                } else {
                    BufferedOutputStream(FileOutputStream(partFile, append))
                }

                BufferedInputStream(body.byteStream()).use { input ->
                    output.use { openedOutput ->
                        val buffer = ByteArray(BUFFER_SIZE_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            openedOutput.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (
                                downloaded - lastReportedBytes >= PROGRESS_REPORT_BYTES ||
                                now - lastReportedAt >= PROGRESS_REPORT_MILLIS
                            ) {
                                openedOutput.flush()
                                val progressUpdated = dao.updateActiveTransfer(
                                    downloadId = downloadId,
                                    state = DownloadStates.DOWNLOADING,
                                    bytesDownloaded = downloaded,
                                    totalBytes = totalBytes,
                                    localRelativePath = destinationLocation,
                                    failureReason = null,
                                    updatedAtEpochMillis = now,
                                )
                                if (progressUpdated == 0) {
                                    throw CancellationException("Download state changed during progress update")
                                }
                                setForeground(createForegroundInfo(initialRow, downloaded, totalBytes))
                                lastReportedBytes = downloaded
                                lastReportedAt = now
                            }
                        }
                    }
                }

                val rowBeforeFinalization = dao.getById(downloadId)
                if (rowBeforeFinalization == null) {
                    OfflineDownloadStorage.deleteLocation(applicationContext, destinationLocation)
                    partFile.delete()
                    return Result.success()
                }
                if (rowBeforeFinalization.state == DownloadStates.PAUSED) {
                    throw CancellationException("Download paused before finalization")
                }
                if (totalBytes != null && downloaded < totalBytes) {
                    throw IOException("Download ended before the expected content length")
                }

                val finalLocation: String
                val finalBytes: Long
                if (usePublicDownloads) {
                    finalLocation = requireNotNull(destinationLocation)
                    OfflineDownloadStorage.publishPublicDownload(applicationContext, finalLocation)
                    finalBytes = OfflineDownloadStorage.locationSize(applicationContext, finalLocation)
                        ?: downloaded
                } else {
                    val finalFile = OfflineDownloadStorage.privateFinalFile(
                        applicationContext,
                        downloadId,
                        initialRow.containerExtension ?: "mp4",
                    )
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    finalLocation = OfflineDownloadStorage.privateRelativePath(finalFile)
                    finalBytes = finalFile.length()
                }

                val completed = dao.updateActiveTransfer(
                    downloadId = downloadId,
                    state = DownloadStates.COMPLETED,
                    bytesDownloaded = finalBytes,
                    totalBytes = totalBytes ?: finalBytes,
                    localRelativePath = finalLocation,
                    failureReason = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                if (completed == 0 && dao.getById(downloadId) == null) {
                    OfflineDownloadStorage.deleteLocation(applicationContext, finalLocation)
                    partFile.delete()
                }
                return Result.success()
            }
        } catch (cancelled: CancellationException) {
            val row = dao.getById(downloadId)
            if (row != null) {
                if (row.state == DownloadStates.PAUSED) {
                    if (
                        activePublicDestination != null &&
                        row.localRelativePath != activePublicDestination
                    ) {
                        OfflineDownloadStorage.deleteLocation(applicationContext, activePublicDestination)
                    }
                } else {
                    val localLocation = row.localRelativePath ?: activePublicDestination
                    dao.updateActiveTransfer(
                        downloadId = downloadId,
                        state = DownloadStates.QUEUED,
                        bytesDownloaded = currentTransferBytes(row, localLocation),
                        totalBytes = row.totalBytes,
                        localRelativePath = localLocation,
                        failureReason = null,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    )
                }
            } else {
                OfflineDownloadStorage.deleteLocation(applicationContext, activePublicDestination)
                OfflineDownloadStorage.partialFile(applicationContext, downloadId).delete()
            }
            throw cancelled
        } catch (_: Exception) {
            val row = dao.getById(downloadId)
            if (row != null) {
                val localLocation = row.localRelativePath ?: activePublicDestination
                return if (
                    markQueuedForRetry(
                        dao = dao,
                        row = row,
                        reason = "Download interrupted",
                        bytesDownloaded = currentTransferBytes(row, localLocation),
                        localLocation = localLocation,
                    )
                ) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
            OfflineDownloadStorage.deleteLocation(applicationContext, activePublicDestination)
            OfflineDownloadStorage.partialFile(applicationContext, downloadId).delete()
            return Result.success()
        } finally {
            database.close()
        }
    }

    private suspend fun recoverFinalizedDownload(
        row: MediaDownloadEntity,
        dao: MediaDownloadDao,
    ): Boolean {
        val finalLocation: String
        val finalized: Boolean
        val actualBytes: Long
        if (OfflineDownloadStorage.isPublicDownloadsLocation(row.localRelativePath)) {
            finalLocation = row.localRelativePath ?: return false
            finalized = OfflineDownloadStorage.isPublishedPublicDownload(
                applicationContext,
                finalLocation,
            ) == true
            actualBytes = OfflineDownloadStorage.locationSize(applicationContext, finalLocation)
                ?: return false
        } else {
            val finalFile = OfflineDownloadStorage.privateFinalFile(
                applicationContext,
                row.downloadId,
                row.containerExtension ?: "mp4",
            )
            finalized = finalFile.isFile
            actualBytes = finalFile.takeIf(File::isFile)?.length() ?: return false
            finalLocation = OfflineDownloadStorage.privateRelativePath(finalFile)
        }
        val finalBytes = OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
            finalized = finalized,
            actualBytes = actualBytes,
            expectedTotalBytes = row.totalBytes,
        ) ?: return false

        val completed = dao.updateActiveTransfer(
            downloadId = row.downloadId,
            state = DownloadStates.COMPLETED,
            bytesDownloaded = finalBytes,
            totalBytes = row.totalBytes ?: finalBytes,
            localRelativePath = finalLocation,
            failureReason = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        if (
            completed > 0 &&
            !OfflineDownloadStorage.isPublicDownloadsLocation(finalLocation)
        ) {
            OfflineDownloadStorage.partialFile(applicationContext, row.downloadId).delete()
        }
        return true
    }

    private fun currentTransferBytes(
        row: MediaDownloadEntity,
        localLocation: String? = row.localRelativePath,
    ): Long {
        if (OfflineDownloadStorage.isPublicDownloadsLocation(localLocation)) {
            return OfflineDownloadStorage.locationSize(applicationContext, localLocation)
                ?: row.bytesDownloaded
        }
        return OfflineDownloadStorage.partialFile(applicationContext, row.downloadId)
            .takeIf(File::isFile)
            ?.length()
            ?: row.bytesDownloaded
    }

    private suspend fun markQueuedForRetry(
        dao: MediaDownloadDao,
        row: MediaDownloadEntity,
        reason: String,
        bytesDownloaded: Long = row.bytesDownloaded,
        totalBytes: Long? = row.totalBytes,
        localLocation: String? = row.localRelativePath,
    ): Boolean =
        dao.updateActiveTransfer(
            downloadId = row.downloadId,
            state = DownloadStates.QUEUED,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            localRelativePath = localLocation,
            failureReason = reason,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ) > 0

    private suspend fun markFailed(
        dao: MediaDownloadDao,
        row: MediaDownloadEntity,
        reason: String,
        bytesDownloaded: Long = row.bytesDownloaded,
        totalBytes: Long? = row.totalBytes,
        localLocation: String? = row.localRelativePath,
    ) {
        if (row.state == DownloadStates.COMPLETED) {
            dao.updateTransfer(
                downloadId = row.downloadId,
                state = DownloadStates.FAILED,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                localRelativePath = localLocation,
                failureReason = reason,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            return
        }
        dao.updateActiveTransfer(
            downloadId = row.downloadId,
            state = DownloadStates.FAILED,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            localRelativePath = localLocation,
            failureReason = reason,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun createForegroundInfo(
        row: MediaDownloadEntity,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): ForegroundInfo {
        ensureNotificationChannel()
        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${row.title}")
            .setContentText(progressLabel(bytesDownloaded, totalBytes))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (totalBytes != null && totalBytes > 0L) {
            val progress = ((bytesDownloaded.toDouble() / totalBytes.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (row.downloadId.hashCode() and 0x3fffffff),
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Offline downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress for movies and series episodes saved for offline playback"
                },
            )
        }
    }

    private fun progressLabel(bytesDownloaded: Long, totalBytes: Long?): String {
        val downloaded = humanBytes(bytesDownloaded)
        val knownTotal = totalBytes?.takeIf { it > 0L }
        val total = knownTotal?.let(::humanBytes)
        if (knownTotal == null || total == null) return downloaded
        val percent = ((bytesDownloaded.coerceAtLeast(0L).toDouble() / knownTotal.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        return "$downloaded / $total · $percent%"
    }

    private fun humanBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1_073_741_824L -> "%.1f GB".format(safe / 1_073_741_824.0)
            safe >= 1_048_576L -> "%.1f MB".format(safe / 1_048_576.0)
            safe >= 1_024L -> "%.1f KB".format(safe / 1_024.0)
            else -> "$safe B"
        }
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private const val PROGRESS_REPORT_BYTES = 1024 * 1024L
        private const val PROGRESS_REPORT_MILLIS = 750L
        private const val NOTIFICATION_CHANNEL_ID = "ownplay_offline_downloads"
        private const val NOTIFICATION_ID_BASE = 4100

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }
}
