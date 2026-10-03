package app.ownplay.mobile.feature.live.domain

import kotlinx.coroutines.flow.StateFlow

enum class LiveRecordingStatus {
    SCHEDULED,
    STARTING,
    RECORDING,
    FINALIZING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
}

enum class LiveRecordingStopPolicy {
    PROGRAM_END,
    DEADLINE,
    ABSOLUTE_TIME,
}

enum class LiveRecordingFinalizationState {
    NOT_STARTED,
    FINALIZING,
    PUBLISHED,
    PARTIAL_PUBLISHED,
    DISCARDED,
}

data class LiveRecording(
    val recordingId: String,
    val sourceId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val status: LiveRecordingStatus,
    val localReference: String? = null,
    val safeError: String? = null,
    val stopPolicy: LiveRecordingStopPolicy = LiveRecordingStopPolicy.PROGRAM_END,
    val deadlineEpochSeconds: Long = endEpochSeconds,
    val actualStartEpochSeconds: Long? = null,
    val captureRequestedEpochSeconds: Long? = null,
    val pendingOutputDescriptor: String? = null,
    val progressBytes: Long = 0L,
    val finalizationState: LiveRecordingFinalizationState = LiveRecordingFinalizationState.NOT_STARTED,
    val partialLocalReference: String? = null,
    val failureReason: String? = null,
) {
    init {
        require(recordingId.isNotBlank())
        require(sourceId.isNotBlank())
        require(channelId.isNotBlank())
        require(title.isNotBlank())
        require(startEpochSeconds > 0L && endEpochSeconds > startEpochSeconds)
        require(deadlineEpochSeconds > startEpochSeconds)
        require(actualStartEpochSeconds == null || actualStartEpochSeconds > 0L)
        require(captureRequestedEpochSeconds == null || captureRequestedEpochSeconds > 0L)
        require(progressBytes >= 0L)
    }
}

interface LiveRecordingRepository {
    val recordings: StateFlow<List<LiveRecording>>

    fun get(recordingId: String): LiveRecording?

    fun put(recording: LiveRecording): Boolean

    /** Implementations must serialize this read/modify/write with put and remove. */
    fun update(recordingId: String, transform: (LiveRecording) -> LiveRecording): Boolean

    fun remove(recordingId: String): Boolean
}
