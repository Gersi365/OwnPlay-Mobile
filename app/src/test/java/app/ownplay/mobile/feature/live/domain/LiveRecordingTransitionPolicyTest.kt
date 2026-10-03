package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.*
import org.junit.Test

class LiveRecordingTransitionPolicyTest {
    private fun recording(status: LiveRecordingStatus) = LiveRecording(
        "recording", "source", "channel", "Channel", "Program", 100, 300, status,
        captureRequestedEpochSeconds = 100,
    )

    @Test fun connectionDelayIsExcludedAndStartIsSetOnce() {
        val starting = recording(LiveRecordingStatus.STARTING)
        assertNull(starting.actualStartEpochSeconds)
        val active = LiveRecordingTransitionPolicy.firstValidatedMedia(starting, 125)
        assertEquals(100L, active.captureRequestedEpochSeconds)
        assertEquals(125L, active.actualStartEpochSeconds)
        assertEquals(LiveRecordingStatus.RECORDING, active.status)
        assertEquals(active, LiveRecordingTransitionPolicy.firstValidatedMedia(active, 140))
    }

    @Test fun stopDuringStartupCannotBeUndoneByFirstBytesOrProgress() {
        val stopping = LiveRecordingTransitionPolicy.stopAndSave(recording(LiveRecordingStatus.STARTING))
        assertEquals(stopping, LiveRecordingTransitionPolicy.stopAndSave(stopping))
        val received = LiveRecordingTransitionPolicy.firstValidatedMedia(stopping, 125)
        assertEquals(LiveRecordingStatus.FINALIZING, received.status)
        assertEquals(125L, received.actualStartEpochSeconds)
        assertEquals(LiveRecordingStatus.FINALIZING, LiveRecordingTransitionPolicy.progress(received, 12032).status)
    }

    @Test fun terminalStatesNeverRestartAndProgressNeverMovesBackwards() {
        for (status in listOf(LiveRecordingStatus.COMPLETED, LiveRecordingStatus.PARTIAL, LiveRecordingStatus.FAILED, LiveRecordingStatus.CANCELLED)) {
            val terminal = recording(status)
            assertEquals(terminal, LiveRecordingTransitionPolicy.firstValidatedMedia(terminal, 125))
            assertEquals(terminal, LiveRecordingTransitionPolicy.stopAndSave(terminal))
            assertEquals(terminal, LiveRecordingTransitionPolicy.progress(terminal, 18800))
        }
        val active = recording(LiveRecordingStatus.RECORDING).copy(progressBytes = 18800)
        assertEquals(active, LiveRecordingTransitionPolicy.progress(active, 188))
    }
}
