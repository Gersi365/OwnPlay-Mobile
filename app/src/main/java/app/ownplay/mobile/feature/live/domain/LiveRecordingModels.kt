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
    DELETE_PENDING,
}

enum class LiveRecordingStopPolicy {
    PROGRAM_END,
    DEADLINE,
    ABSOLUTE_TIME,
}

enum class LiveRecordingFinalizationState {
    NOT_STARTED,
    FINALIZING,
    COMPLETED_FINALIZING,
    PUBLISHED,
    PARTIAL_PUBLISHED,
    PARTIAL_FINALIZING,
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
    val failureReasonCode: String? = null,
    val failureStage: String? = null,
    val failureAtEpochMs: Long? = null,
    val scheduleArmedState: String? = null,
    val scheduledAtEpochMs: Long? = null,
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
        require(failureAtEpochMs == null || failureAtEpochMs > 0L)
        require(scheduledAtEpochMs == null || scheduledAtEpochMs > 0L)
    }
}

/** Stable, safe-to-persist diagnostic identifiers. Unknown values remain readable as opaque strings. */
object LiveRecordingFailureCodes {
    const val START_WINDOW_MISSED = "START_WINDOW_MISSED"
    const val EXACT_ALARM_PERMISSION_MISSING = "EXACT_ALARM_PERMISSION_MISSING"
    const val EXACT_ALARM_PERMISSION_REVOKED = "EXACT_ALARM_PERMISSION_REVOKED"
    const val EXACT_ALARM_SCHEDULE_ERROR = "EXACT_ALARM_SCHEDULE_ERROR"
    const val ANDROID_BACKGROUND_START_DENIED = "ANDROID_BACKGROUND_START_DENIED"
    const val ANDROID_SERVICE_TIME_LIMIT = "ANDROID_SERVICE_TIME_LIMIT"
    const val NOTIFICATION_PERMISSION_MISSING = "NOTIFICATION_PERMISSION_MISSING"
    const val LIVE_SLOT_USED_BY_PLAYBACK = "LIVE_SLOT_USED_BY_PLAYBACK"
    const val LIVE_SLOT_USED_BY_RECORDING = "LIVE_SLOT_USED_BY_RECORDING"
    const val APP_RECORDING_SERVICE_BUSY = "APP_RECORDING_SERVICE_BUSY"
    const val PLAYBACK_PREEMPTED_RECORDING = "PLAYBACK_PREEMPTED_RECORDING"
    const val USER_STOPPED = "USER_STOPPED"
    const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
    const val AUTHORIZATION = "RECORDING_AUTHORIZATION_FAILURE"
    const val UNSUPPORTED_FORMAT = "RECORDING_UNSUPPORTED_FORMAT"
    const val STORAGE = "RECORDING_STORAGE_FAILURE"
    const val SOURCE_UNAVAILABLE = "RECORDING_SOURCE_UNAVAILABLE"
    const val NETWORK = "RECORDING_NETWORK_FAILURE"
    const val UNKNOWN = "RECORDING_UNKNOWN_FAILURE"
}

object LiveRecordingFailureStages {
    const val SCHEDULE = "SCHEDULE"
    const val ALARM_ARMING = "ALARM_ARMING"
    const val DUE_START = "DUE_START"
    const val CAPACITY_ADMISSION = "CAPACITY_ADMISSION"
    const val FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
    const val CAPTURE = "CAPTURE"
    const val FINALIZATION = "FINALIZATION"
    const val RECOVERY = "RECOVERY"
    const val PLAYBACK_PREEMPTION = "PLAYBACK_PREEMPTION"
    const val USER_ACTION = "USER_ACTION"
}

object LiveRecordingScheduleArmedStates {
    const val ARMED = "ARMED"
    const val NOT_ARMED_PERMISSION = "NOT_ARMED_PERMISSION"
    const val NOT_ARMED_ERROR = "NOT_ARMED_ERROR"
}

interface LiveRecordingRepository {
    val recordings: StateFlow<List<LiveRecording>>

    fun get(recordingId: String): LiveRecording?

    fun put(recording: LiveRecording): Boolean

    /** Implementations must serialize this read/modify/write with put and remove. */
    fun update(recordingId: String, transform: (LiveRecording) -> LiveRecording): Boolean

    fun remove(recordingId: String): Boolean
}
