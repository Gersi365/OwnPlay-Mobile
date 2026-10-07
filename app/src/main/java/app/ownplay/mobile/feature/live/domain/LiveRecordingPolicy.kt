package app.ownplay.mobile.feature.live.domain

import java.security.MessageDigest

enum class LiveRecordingAction {
    RECORD_NOW,
    SCHEDULE,
}

object LiveRecordingPolicy {
    fun actionFor(program: LiveProgram, nowEpochSeconds: Long): LiveRecordingAction? {
        val start = program.startEpochSeconds ?: return null
        val end = program.endEpochSeconds ?: return null
        if (program.title.isBlank() || start <= 0L || end <= start || end <= nowEpochSeconds) {
            return null
        }
        return if (start <= nowEpochSeconds) LiveRecordingAction.RECORD_NOW else LiveRecordingAction.SCHEDULE
    }

    fun recordingId(sourceId: String, channelId: String, startEpochSeconds: Long): String {
        val bytes = "$sourceId|$channelId|$startEpochSeconds"
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

object LiveRecordingRemovalPolicy {
    fun canRemove(status: LiveRecordingStatus): Boolean =
        status !in setOf(
            LiveRecordingStatus.STARTING,
            LiveRecordingStatus.RECORDING,
            LiveRecordingStatus.FINALIZING,
            LiveRecordingStatus.DELETE_PENDING,
        )

    fun canDeleteLocalFile(recording: LiveRecording): Boolean =
        recording.status in setOf(
            LiveRecordingStatus.COMPLETED,
            LiveRecordingStatus.PARTIAL,
        ) && !recording.localReference.isNullOrBlank()
}
