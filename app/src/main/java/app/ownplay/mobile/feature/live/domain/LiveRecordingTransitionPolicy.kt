package app.ownplay.mobile.feature.live.domain

object LiveRecordingTransitionPolicy {
    fun firstValidatedMedia(recording: LiveRecording, receivedAtEpochSeconds: Long): LiveRecording {
        if (recording.status !in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.FINALIZING)) return recording
        if (recording.actualStartEpochSeconds != null) return recording
        return recording.copy(
            status = if (recording.status == LiveRecordingStatus.STARTING) LiveRecordingStatus.RECORDING else recording.status,
            actualStartEpochSeconds = receivedAtEpochSeconds,
        )
    }

    fun stopAndSave(recording: LiveRecording): LiveRecording =
        if (recording.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING)) {
            recording.copy(status = LiveRecordingStatus.FINALIZING, finalizationState = LiveRecordingFinalizationState.FINALIZING)
        } else recording

    fun progress(recording: LiveRecording, bytes: Long): LiveRecording =
        if (recording.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING, LiveRecordingStatus.FINALIZING)) {
            recording.copy(progressBytes = maxOf(recording.progressBytes, bytes))
        } else recording
}
