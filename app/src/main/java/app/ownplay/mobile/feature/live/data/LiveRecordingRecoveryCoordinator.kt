package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.DownloadStorage
import app.ownplay.mobile.downloads.data.PendingDownloadOutput
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureStages
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reconciles recording output through an injectable storage boundary for deterministic restart tests. */
internal class LiveRecordingRecoveryCoordinator(
    private val repository: LiveRecordingRepository,
    private val storage: DownloadStorage,
) {
    private val finalizationCommitter = LiveRecordingFinalizationCommitter(repository, storage)

    suspend fun recover(interrupted: List<LiveRecording>) = withContext(Dispatchers.IO) {
        for (recording in interrupted) {
            val current = repository.get(recording.recordingId) ?: continue
            if (current != recording) continue
            val downloadId = DownloadId("recording-" + recording.recordingId)
            val descriptor = recording.pendingOutputDescriptor
            if (descriptor.isNullOrBlank()) {
                repository.update(recording.recordingId) { latest -> interruptedFailure(latest) }
                continue
            }
            if (recording.finalizationState in setOf(
                    LiveRecordingFinalizationState.PUBLISHED,
                    LiveRecordingFinalizationState.PARTIAL_PUBLISHED,
                ) && !recording.localReference.isNullOrBlank()
            ) {
                if (storage.finalizeRecoveredPublished(downloadId, descriptor, recording.localReference!!)) {
                    repository.update(recording.recordingId) { latest -> latest.copy(pendingOutputDescriptor = null) }
                }
                continue
            }

            var pending: PendingDownloadOutput? = null
            var reconciled = false
            var preserveFinalization = false
            try {
                val published = storage.recoverOwnedPublished(downloadId, descriptor)
                if (published != null) {
                    val validator = newRecoveryValidator()
                    var unreadable = false
                    val valid = try {
                        val copied = published.inputStream.use { it.copyTo(validator) }
                        validator.isValid && isPlausibleTransportStream(copied) && copied == published.verifiedSize &&
                            validator.bytesWritten == copied
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        unreadable = true
                        false
                    }
                    if (valid) {
                        preserveFinalization = true
                        val targetPartial = recording.finalizationState !in setOf(
                            LiveRecordingFinalizationState.COMPLETED_FINALIZING,
                            LiveRecordingFinalizationState.PUBLISHED,
                        )
                        reconciled = persistRecoveredRecording(
                            recording = recording,
                            descriptor = descriptor,
                            reference = published.localReference,
                            size = published.verifiedSize,
                            partial = targetPartial,
                            processInterrupted = true,
                            partialWasUserStopped = recording.failureReasonCode == LiveRecordingFailureCodes.USER_STOPPED ||
                                recording.failureReason == "STOPPED_BY_USER",
                        )
                    } else if (unreadable) {
                        preserveFinalization = true
                    } else if (removeOwnedPublishedSafely(published.localReference)) {
                        reconciled = repository.update(recording.recordingId) { latest -> interruptedFailure(latest) }
                    } else {
                        preserveFinalization = true
                    }
                } else {
                    pending = storage.recoverOwnedPending(downloadId, descriptor)
                }
                if (pending != null) {
                    if (storage.pendingOutputMissing(pending)) {
                        throw IOException("Pending recording output is already absent")
                    }
                    val validator = newRecoveryValidator()
                    val input = try {
                        storage.openPendingInput(pending)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        preserveFinalization = true
                        null
                    }
                    if (input == null) {
                        if (!storage.pendingOutputMissing(pending)) preserveFinalization = true
                        throw IOException("Pending output is unavailable")
                    }
                    val copied = try {
                        input.use { it.copyTo(validator) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        preserveFinalization = true
                        throw IOException("Pending recording output could not be read")
                    }
                    validator.ensureValid()
                    val size = try {
                        storage.verifiedSize(pending)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        preserveFinalization = true
                        null
                    } ?: run {
                        preserveFinalization = true
                        throw IOException("Pending recording size is unavailable")
                    }
                    if (!isPlausibleTransportStream(size) || copied != size || validator.bytesWritten != size) {
                        throw IOException("Pending recording failed content validation")
                    }
                    val targetPartial = recording.finalizationState !in setOf(
                        LiveRecordingFinalizationState.COMPLETED_FINALIZING,
                        LiveRecordingFinalizationState.PUBLISHED,
                    )
                    preserveFinalization = true
                    reconciled = publishRecoveredRecording(
                        recording = recording,
                        descriptor = descriptor,
                        pending = pending,
                        size = size,
                        partial = targetPartial,
                        processInterrupted = true,
                        partialWasUserStopped = recording.failureReasonCode == LiveRecordingFailureCodes.USER_STOPPED ||
                            recording.failureReason == "STOPPED_BY_USER",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Source URLs and platform exception messages are never persisted here.
            }
            if (!reconciled && !preserveFinalization && pending == null) {
                // A null provider/path result can mean inaccessible, not absent; retain the exact descriptor.
                preserveFinalization = true
            }
            if (!reconciled && !preserveFinalization && discardPendingSafely(pending)) {
                repository.update(recording.recordingId) { latest -> interruptedFailure(latest) }
            }
        }
    }

    private fun newRecoveryValidator() = TransportStreamValidatingOutputStream(object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    })

    private fun interruptedFailure(recording: LiveRecording): LiveRecording {
        val reason = recording.failureReasonCode ?: when (recording.failureReason) {
            "STOPPED_BY_USER" -> LiveRecordingFailureCodes.USER_STOPPED
            "ANDROID_FGS_TIME_LIMIT" -> LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT
            "PROCESS_INTERRUPTED" -> LiveRecordingFailureCodes.PROCESS_INTERRUPTED
            else -> LiveRecordingFailureCodes.PROCESS_INTERRUPTED
        }
        return recording.copy(
            status = LiveRecordingStatus.FAILED,
            safeError = when (reason) {
                LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING ->
                "Playback took priority; no verified partial recording was recovered."
                LiveRecordingFailureCodes.USER_STOPPED ->
                "Recording stopped; no verified partial output was recovered."
                LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT ->
                "Android ended the recording service; no verified partial output was recovered."
                else -> recording.safeError ?: "Recording was interrupted; no verified output was recovered."
            },
            failureReason = recording.failureReason ?: reason,
            failureReasonCode = reason,
            failureStage = recording.failureStage ?: LiveRecordingFailureStages.RECOVERY,
            failureAtEpochMs = recording.failureAtEpochMs ?: System.currentTimeMillis(),
            finalizationState = LiveRecordingFinalizationState.DISCARDED,
            pendingOutputDescriptor = null,
        )
    }

    private suspend fun publishRecoveredRecording(
        recording: LiveRecording,
        descriptor: String,
        pending: PendingDownloadOutput,
        size: Long,
        partial: Boolean,
        processInterrupted: Boolean,
        partialWasUserStopped: Boolean,
    ): Boolean {
        val intentState = if (partial) LiveRecordingFinalizationState.PARTIAL_FINALIZING
        else LiveRecordingFinalizationState.COMPLETED_FINALIZING
        val intent = recording.copy(
            status = LiveRecordingStatus.FINALIZING,
            finalizationState = intentState,
            pendingOutputDescriptor = descriptor,
        )
        if (!repository.put(intent)) return false
        val reference = try {
            storage.publish(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return true
        } ?: return true
        return persistRecoveredRecording(
            recording = intent,
            descriptor = descriptor,
            reference = reference,
            size = size,
            partial = partial,
            processInterrupted = processInterrupted,
            partialWasUserStopped = partialWasUserStopped,
            pending = pending,
        )
    }

    private suspend fun persistRecoveredRecording(
        recording: LiveRecording,
        descriptor: String,
        reference: String,
        size: Long,
        partial: Boolean,
        processInterrupted: Boolean,
        partialWasUserStopped: Boolean,
        pending: PendingDownloadOutput? = null,
    ): Boolean = finalizationCommitter.commitPublished(
        recording = recording,
        descriptor = descriptor,
        reference = reference,
        size = size,
        partial = partial,
        processInterrupted = processInterrupted,
        partialWasUserStopped = partialWasUserStopped,
        pending = pending,
    )

    private suspend fun removeOwnedPublishedSafely(reference: String): Boolean = try {
        storage.removePublished(reference)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun discardPendingSafely(pending: PendingDownloadOutput?): Boolean {
        pending ?: return true
        return try {
            storage.discard(pending)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun isPlausibleTransportStream(size: Long): Boolean =
        size >= MIN_PARTIAL_TS_BYTES && size % TS_PACKET_BYTES == 0L

    private companion object {
        const val TS_PACKET_BYTES = 188L
        const val MIN_PARTIAL_TS_BYTES = TS_PACKET_BYTES * 64L
    }
}
