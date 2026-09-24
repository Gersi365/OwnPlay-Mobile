package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadProgressThrottlePolicy
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadTransferIntegrityPolicy
import kotlinx.coroutines.CancellationException

internal enum class DownloadExecutionOutcome {
    COMPLETED,
    SKIPPED,
    PERSISTED_FAILURE,
}

internal class DownloadExecutor(
    private val repository: DownloadRepository,
    private val mediaResolver: DownloadMediaResolver,
    private val storage: DownloadStorage,
    private val transferClient: DownloadTransferClient,
    private val progressPolicy: DownloadProgressThrottlePolicy = DownloadProgressThrottlePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(downloadId: DownloadId): DownloadExecutionOutcome {
        val initial = repository.get(downloadId) ?: return DownloadExecutionOutcome.SKIPPED
        if (initial.status != DownloadStatus.DOWNLOADING) {
            return DownloadExecutionOutcome.SKIPPED
        }

        val media = mediaResolver.resolve(initial)
        if (media == null) {
            persistFailureIfActive(downloadId, DownloadFailureCode.SOURCE_UNAVAILABLE)
            return DownloadExecutionOutcome.PERSISTED_FAILURE
        }

        repository.updateProgress(downloadId, 0L, initial.totalBytes)

        val pending = try {
            storage.openPending(downloadId, media)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (pending == null) {
            persistFailureIfActive(downloadId, DownloadFailureCode.STORAGE)
            return DownloadExecutionOutcome.PERSISTED_FAILURE
        }

        return try {
            var lastPublishedBytes = 0L
            var lastPublishedAt = clock()
            val transfer = pending.outputStream.use { output ->
                transferClient.transfer(media.uri, output) { bytes, reportedTotal ->
                    val now = clock()
                    if (
                        progressPolicy.shouldPublish(
                            previousBytes = lastPublishedBytes,
                            previousAtMs = lastPublishedAt,
                            currentBytes = bytes,
                            currentAtMs = now,
                        )
                    ) {
                        val total = initial.totalBytes ?: reportedTotal
                        if (repository.updateProgress(downloadId, bytes, total)) {
                            lastPublishedBytes = bytes
                            lastPublishedAt = now
                        }
                    }
                }
            }

            val expectedBytes = initial.totalBytes ?: transfer.reportedContentLength
            repository.updateProgress(
                downloadId = downloadId,
                bytesDownloaded = transfer.bytesTransferred,
                totalBytes = expectedBytes,
            )
            val storedBytes = storage.verifiedSize(pending)
            if (
                !DownloadTransferIntegrityPolicy.isValid(
                    transferredBytes = transfer.bytesTransferred,
                    expectedBytes = expectedBytes,
                    storedBytes = storedBytes,
                )
            ) {
                storage.discard(pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.INTEGRITY)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }

            if (repository.get(downloadId)?.status != DownloadStatus.DOWNLOADING) {
                storage.discard(pending)
                return DownloadExecutionOutcome.SKIPPED
            }

            val localReference = storage.publish(pending)
            if (localReference == null) {
                storage.discard(pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.STORAGE)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }

            val completed = repository.complete(
                downloadId = downloadId,
                localReference = localReference,
                verifiedBytes = transfer.bytesTransferred,
                sha256 = transfer.sha256,
            )
            if (!completed) {
                storage.discard(pending)
                if (repository.get(downloadId)?.status == DownloadStatus.DOWNLOADING) {
                    persistFailureIfActive(downloadId, DownloadFailureCode.UNKNOWN)
                    return DownloadExecutionOutcome.PERSISTED_FAILURE
                }
                return DownloadExecutionOutcome.SKIPPED
            }
            DownloadExecutionOutcome.COMPLETED
        } catch (cancelled: CancellationException) {
            storage.discard(pending)
            throw cancelled
        } catch (error: DownloadTransferException) {
            storage.discard(pending)
            persistFailureIfActive(downloadId, error.failure.toFailureCode())
            DownloadExecutionOutcome.PERSISTED_FAILURE
        } catch (_: Exception) {
            storage.discard(pending)
            persistFailureIfActive(downloadId, DownloadFailureCode.UNKNOWN)
            DownloadExecutionOutcome.PERSISTED_FAILURE
        }
    }

    private suspend fun persistFailureIfActive(
        downloadId: DownloadId,
        failureCode: DownloadFailureCode,
    ) {
        val status = repository.get(downloadId)?.status ?: return
        if (status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) {
            repository.fail(downloadId, failureCode)
        }
    }

    private fun DownloadTransferFailure.toFailureCode(): DownloadFailureCode = when (this) {
        DownloadTransferFailure.NETWORK -> DownloadFailureCode.NETWORK
        DownloadTransferFailure.TIMEOUT -> DownloadFailureCode.TIMEOUT
        DownloadTransferFailure.SOURCE_UNAVAILABLE -> DownloadFailureCode.SOURCE_UNAVAILABLE
    }
}
