package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRecordingPolicyTest {
    @Test
    fun currentProgramRecordsNowAndFutureProgramSchedules() {
        assertEquals(
            LiveRecordingAction.RECORD_NOW,
            LiveRecordingPolicy.actionFor(LiveProgram("Now", 90, 110), 100),
        )
        assertEquals(
            LiveRecordingAction.SCHEDULE,
            LiveRecordingPolicy.actionFor(LiveProgram("Next", 110, 130), 100),
        )
    }

    @Test
    fun pastUntimedAndInvalidProgramsHaveNoRecordingAction() {
        assertNull(LiveRecordingPolicy.actionFor(LiveProgram("Past", 50, 99), 100))
        assertNull(LiveRecordingPolicy.actionFor(LiveProgram("Untimed", null, null), 100))
        assertNull(LiveRecordingPolicy.actionFor(LiveProgram("Invalid", 110, 100), 100))
    }

    @Test
    fun recordingRemovalPolicyProtectsActiveCaptureAndAllowsTerminalRows() {
        assertFalse(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.STARTING))
        assertFalse(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.RECORDING))
        assertFalse(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.FINALIZING))
        assertTrue(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.SCHEDULED))
        assertTrue(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.COMPLETED))
        assertTrue(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.PARTIAL))
        assertTrue(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.FAILED))
        assertTrue(LiveRecordingRemovalPolicy.canRemove(LiveRecordingStatus.CANCELLED))
    }

    @Test
    fun localRecordingDeleteIsOnlyOfferedForPublishedCompletedOrPartialMedia() {
        val completed = recording(LiveRecordingStatus.COMPLETED, "content://media/external/downloads/42")
        assertTrue(LiveRecordingRemovalPolicy.canDeleteLocalFile(completed))
        assertTrue(
            LiveRecordingRemovalPolicy.canDeleteLocalFile(
                completed.copy(status = LiveRecordingStatus.PARTIAL),
            ),
        )
        assertFalse(
            LiveRecordingRemovalPolicy.canDeleteLocalFile(
                completed.copy(status = LiveRecordingStatus.FAILED),
            ),
        )
        assertFalse(LiveRecordingRemovalPolicy.canDeleteLocalFile(completed.copy(localReference = null)))
    }

    private fun recording(
        status: LiveRecordingStatus,
        localReference: String?,
    ) = LiveRecording(
        recordingId = "recording",
        sourceId = "source",
        channelId = "channel",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = 1_000L,
        endEpochSeconds = 2_000L,
        status = status,
        localReference = localReference,
    )

}
