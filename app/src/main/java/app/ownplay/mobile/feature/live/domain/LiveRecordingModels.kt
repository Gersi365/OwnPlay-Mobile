package app.ownplay.mobile.feature.live.domain

import kotlinx.coroutines.flow.StateFlow

enum class LiveRecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELLED,
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
) {
    init {
        require(recordingId.isNotBlank())
        require(sourceId.isNotBlank())
        require(channelId.isNotBlank())
        require(title.isNotBlank())
        require(startEpochSeconds > 0L && endEpochSeconds > startEpochSeconds)
    }
}

interface LiveRecordingRepository {
    val recordings: StateFlow<List<LiveRecording>>

    fun get(recordingId: String): LiveRecording?

    fun put(recording: LiveRecording): Boolean

    fun remove(recordingId: String): Boolean
}
