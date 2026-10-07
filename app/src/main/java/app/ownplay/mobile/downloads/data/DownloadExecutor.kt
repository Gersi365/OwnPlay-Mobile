package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadFinalizationPhase
import app.ownplay.mobile.downloads.domain.DownloadIntegrityPolicy
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadProgressThrottlePolicy
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadTransferIntegrityPolicy
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val locks = ConcurrentHashMap<DownloadId, Mutex>()

    suspend fun execute(downloadId: DownloadId): DownloadExecutionOutcome =
        lock(downloadId) { executeLocked(downloadId) }

    suspend fun recoverInterruptedFinalizations(items: List<DownloadItem>) {
        for (item in items.filter { it.finalization != null }) {
            try {
                lock(item.downloadId) {
                    val current = repository.get(item.downloadId)
                    if (current != null) reconcileFinalization(current)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep durable recovery state for the next worker/startup attempt.
            }
        }
    }

    private suspend fun executeLocked(downloadId: DownloadId): DownloadExecutionOutcome {
        var initial = repository.get(downloadId) ?: return DownloadExecutionOutcome.SKIPPED
        if (initial.finalization != null) {
            val recovery = try {
                reconcileFinalization(initial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }
            when (recovery) {
                RecoveryResult.COMPLETED -> return DownloadExecutionOutcome.COMPLETED
                RecoveryResult.BLOCKED -> return DownloadExecutionOutcome.PERSISTED_FAILURE
                RecoveryResult.RETRY_TRANSFER -> {
                    initial = repository.get(downloadId) ?: return DownloadExecutionOutcome.SKIPPED
                }
            }
        }
        if (initial.status != DownloadStatus.DOWNLOADING) return DownloadExecutionOutcome.SKIPPED

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

        val descriptor = try {
            storage.describePending(pending)
        } catch (_: Exception) {
            storage.discard(pending)
            persistFailureIfActive(downloadId, DownloadFailureCode.STORAGE)
            return DownloadExecutionOutcome.PERSISTED_FAILURE
        }
        if (!repository.recordOutputIntent(downloadId, descriptor)) {
            storage.discard(pending)
            return DownloadExecutionOutcome.SKIPPED
        }

        var publishedReference: String? = null
        var metadataCommitted = false
        return try {
            var lastPublishedBytes = 0L
            var lastPublishedAt = clock()
            val transfer = pending.outputStream.use { output ->
                val publishProgress: suspend (Long, Long?) -> Unit = { bytes, reportedTotal ->
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
                if (media.requiresReportedContentLength) {
                    transferClient.transferFinite(media.uri, output, publishProgress)
                } else {
                    transferClient.transfer(media.uri, output, publishProgress)
                }
            }

            if (media.requiresReportedContentLength && transfer.reportedContentLength == null) {
                discardBeforePublish(downloadId, pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.INTEGRITY)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }

            val expectedBytes = initial.totalBytes ?: transfer.reportedContentLength
            repository.updateProgress(downloadId, transfer.bytesTransferred, expectedBytes)
            val storedBytes = storage.verifiedSize(pending)
            if (
                !DownloadTransferIntegrityPolicy.isValid(
                    transferredBytes = transfer.bytesTransferred,
                    expectedBytes = expectedBytes,
                    storedBytes = storedBytes,
                )
            ) {
                discardBeforePublish(downloadId, pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.INTEGRITY)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }
            val digest = DownloadIntegrityPolicy.normalizeSha256(transfer.sha256)
            if (digest == null || !repository.markOutputVerified(downloadId, transfer.bytesTransferred, digest)) {
                discardBeforePublish(downloadId, pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.INTEGRITY)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }

            if (repository.get(downloadId)?.status != DownloadStatus.DOWNLOADING) {
                discardBeforePublish(downloadId, pending)
                return DownloadExecutionOutcome.SKIPPED
            }

            val localReference = storage.publish(pending)
            publishedReference = localReference
            if (localReference == null) {
                discardBeforePublish(downloadId, pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.STORAGE)
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }

            val completed = try {
                repository.complete(
                    downloadId = downloadId,
                    localReference = localReference,
                    verifiedBytes = transfer.bytesTransferred,
                    sha256 = digest,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The verified publish and its rollback descriptor remain durable for restart recovery.
                return DownloadExecutionOutcome.PERSISTED_FAILURE
            }
            if (!completed) {
                if (repository.get(downloadId)?.status == DownloadStatus.DOWNLOADING) {
                    return DownloadExecutionOutcome.PERSISTED_FAILURE
                }
                storage.discard(pending)
                repository.clearOutputFinalization(downloadId)
                return DownloadExecutionOutcome.SKIPPED
            }
            metadataCommitted = true
            if (storage.finalizePublished(pending, localReference)) {
                repository.clearOutputFinalization(downloadId)
            }
            DownloadExecutionOutcome.COMPLETED
        } catch (cancelled: CancellationException) {
            if (publishedReference == null) discardBeforePublish(downloadId, pending)
            throw cancelled
        } catch (_: Exception) {
            if (publishedReference == null) {
                discardBeforePublish(downloadId, pending)
                persistFailureIfActive(downloadId, DownloadFailureCode.UNKNOWN)
            }
            // After publish, leave the durable descriptor in place; startup/worker retry reconciles it.
            if (metadataCommitted) DownloadExecutionOutcome.COMPLETED else DownloadExecutionOutcome.PERSISTED_FAILURE
        }
    }

    private enum class RecoveryResult { COMPLETED, RETRY_TRANSFER, BLOCKED }

    private companion object {
        val FINALIZABLE_STATES = setOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.QUEUED,
            DownloadStatus.WAITING_FOR_WIFI,
        )
    }

    private suspend fun reconcileFinalization(item: DownloadItem): RecoveryResult {
        val finalization = item.finalization ?: return RecoveryResult.RETRY_TRANSFER
        if (item.status == DownloadStatus.COMPLETED) {
            val reference = item.localReference ?: return RecoveryResult.BLOCKED
            val cleaned = storage.finalizeRecoveredPublished(item.downloadId, finalization.descriptor, reference)
            if (cleaned && repository.clearOutputFinalization(item.downloadId)) return RecoveryResult.COMPLETED
            return RecoveryResult.BLOCKED
        }

        if (finalization.phase == DownloadFinalizationPhase.STAGING) {
            val pending = storage.recoverOwnedPending(item.downloadId, finalization.descriptor)
                ?: return RecoveryResult.BLOCKED
            storage.discard(pending)
            return if (repository.clearOutputFinalization(item.downloadId)) RecoveryResult.RETRY_TRANSFER else RecoveryResult.BLOCKED
        }

        val expectedBytes = finalization.verifiedBytes ?: return RecoveryResult.BLOCKED
        val expectedHash = DownloadIntegrityPolicy.normalizeSha256(finalization.sha256) ?: return RecoveryResult.BLOCKED
        val published = storage.recoverOwnedPublished(item.downloadId, finalization.descriptor)
        if (published != null) {
            val actual = published.inputStream.use(::sha256AndSize)
            if (actual.first != expectedBytes || published.verifiedSize != expectedBytes || actual.second != expectedHash) {
                val rolledBack = storage.rollbackRecoveredPublished(
                    item.downloadId,
                    finalization.descriptor,
                    expectedBytes,
                    expectedHash,
                )
                if (!rolledBack || !repository.clearOutputFinalization(item.downloadId)) return RecoveryResult.BLOCKED
                return if (item.status in FINALIZABLE_STATES) RecoveryResult.RETRY_TRANSFER else RecoveryResult.COMPLETED
            }
            if (item.status !in FINALIZABLE_STATES) {
                val rolledBack = storage.rollbackRecoveredPublished(item.downloadId, finalization.descriptor, expectedBytes, expectedHash)
                if (rolledBack) repository.clearOutputFinalization(item.downloadId)
                return if (rolledBack) RecoveryResult.COMPLETED else RecoveryResult.BLOCKED
            }
            val committed = repository.complete(item.downloadId, published.localReference, expectedBytes, expectedHash)
            if (!committed) return RecoveryResult.BLOCKED
            val cleaned = storage.finalizeRecoveredPublished(item.downloadId, finalization.descriptor, published.localReference)
            if (cleaned) repository.clearOutputFinalization(item.downloadId)
            return if (cleaned) RecoveryResult.COMPLETED else RecoveryResult.BLOCKED
        }

        val pending = storage.recoverOwnedPending(item.downloadId, finalization.descriptor)
            ?: return RecoveryResult.BLOCKED
        if (storage.pendingOutputMissing(pending)) {
            storage.discard(pending)
            val cleared = repository.clearOutputFinalization(item.downloadId)
            return if (!cleared) RecoveryResult.BLOCKED else if (item.status in FINALIZABLE_STATES) {
                RecoveryResult.RETRY_TRANSFER
            } else {
                RecoveryResult.COMPLETED
            }
        }
        val storedSize = storage.verifiedSize(pending)
        if (storedSize == null) return RecoveryResult.BLOCKED
        val input = storage.openPendingInput(pending)
        if (input == null) {
            return RecoveryResult.BLOCKED
        }
        val actual = input.use(::sha256AndSize)
        if (storedSize != expectedBytes || actual.first != expectedBytes || actual.second != expectedHash) {
            storage.discard(pending)
            return if (repository.clearOutputFinalization(item.downloadId)) RecoveryResult.RETRY_TRANSFER else RecoveryResult.BLOCKED
        }
        if (item.status !in FINALIZABLE_STATES) {
            storage.discard(pending)
            return if (repository.clearOutputFinalization(item.downloadId)) RecoveryResult.COMPLETED else RecoveryResult.BLOCKED
        }
        val reference = storage.publish(pending) ?: run {
            storage.discard(pending)
            return RecoveryResult.BLOCKED
        }
        val committed = repository.complete(item.downloadId, reference, expectedBytes, expectedHash)
        if (!committed) {
            if (repository.get(item.downloadId)?.status != DownloadStatus.DOWNLOADING) {
                storage.discard(pending)
                repository.clearOutputFinalization(item.downloadId)
            }
            return RecoveryResult.BLOCKED
        }
        val cleaned = storage.finalizePublished(pending, reference)
        if (cleaned) repository.clearOutputFinalization(item.downloadId)
        return if (cleaned) RecoveryResult.COMPLETED else RecoveryResult.BLOCKED
    }

    private suspend fun discardBeforePublish(downloadId: DownloadId, pending: PendingDownloadOutput) {
        val discarded = try {
            storage.discard(pending)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (discarded) {
            try {
                repository.clearOutputFinalization(downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A stale but safe descriptor can be reconciled again on the next run.
            }
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

    private suspend fun <T> lock(downloadId: DownloadId, action: suspend () -> T): T =
        locks.getOrPut(downloadId) { Mutex() }.withLock { action() }

    private fun sha256AndSize(input: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            digest.update(buffer, 0, count)
        }
        return size to digest.digest().joinToString("") { "%02x".format(it) }
    }
}
