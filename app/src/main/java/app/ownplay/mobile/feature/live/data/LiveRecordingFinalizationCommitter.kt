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

/** Commits already-published output metadata before cleaning any recoverable storage backup. */
internal class LiveRecordingFinalizationCommitter(
    private val repository: LiveRecordingRepository,
    private val storage: DownloadStorage,
) {
    suspend fun commitPublished(
        recording: LiveRecording,
        descriptor: String,
        reference: String,
        size: Long,
        partial: Boolean,
        processInterrupted: Boolean,
        partialWasUserStopped: Boolean = recording.failureReasonCode == LiveRecordingFailureCodes.USER_STOPPED ||
            recording.failureReason == "STOPPED_BY_USER",
        pending: PendingDownloadOutput? = null,
    ): Boolean {
        val preservedReasonCode = when {
            !partial -> null
            !recording.failureReasonCode.isNullOrBlank() -> recording.failureReasonCode
            recording.failureReason == "STOPPED_BY_USER" -> LiveRecordingFailureCodes.USER_STOPPED
            recording.failureReason == "PROCESS_INTERRUPTED" -> LiveRecordingFailureCodes.PROCESS_INTERRUPTED
            recording.failureReason == "ANDROID_FGS_TIME_LIMIT" -> LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT
            processInterrupted -> LiveRecordingFailureCodes.PROCESS_INTERRUPTED
            partialWasUserStopped -> LiveRecordingFailureCodes.USER_STOPPED
            else -> null
        }
        val saved = repository.put(
            recording.copy(
                status = if (partial) LiveRecordingStatus.PARTIAL else LiveRecordingStatus.COMPLETED,
                localReference = reference,
                partialLocalReference = reference.takeIf { partial },
                pendingOutputDescriptor = descriptor,
                progressBytes = size,
                finalizationState = if (partial) LiveRecordingFinalizationState.PARTIAL_PUBLISHED
                    else LiveRecordingFinalizationState.PUBLISHED,
                failureReason = preservedReasonCode ?: recording.failureReason.takeIf { partial },
                failureReasonCode = preservedReasonCode,
                failureStage = recording.failureStage ?: when {
                    processInterrupted && partial -> LiveRecordingFailureStages.RECOVERY
                    partialWasUserStopped && partial -> LiveRecordingFailureStages.USER_ACTION
                    else -> null
                },
                failureAtEpochMs = if (preservedReasonCode == null) recording.failureAtEpochMs
                    else recording.failureAtEpochMs ?: System.currentTimeMillis(),
                safeError = when {
                    !partial -> null
                    preservedReasonCode == LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING ->
                        "Playback started; a verified partial recording was saved."
                    preservedReasonCode == LiveRecordingFailureCodes.USER_STOPPED ->
                        "Saved as a partial recording."
                    preservedReasonCode == LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT || processInterrupted ->
                        "Recovered a verified partial recording after service interruption."
                    else -> recording.safeError
                        ?: "Saved a verified partial recording after the stream ended early."
                },
            ),
        )
        if (!saved) return false
        val cleaned = if (pending != null) {
            storage.finalizePublished(pending, reference)
        } else {
            storage.finalizeRecoveredPublished(
                DownloadId("recording-" + recording.recordingId),
                descriptor,
                reference,
            )
        }
        if (cleaned) repository.update(recording.recordingId) { it.copy(pendingOutputDescriptor = null) }
        return true
    }
}
