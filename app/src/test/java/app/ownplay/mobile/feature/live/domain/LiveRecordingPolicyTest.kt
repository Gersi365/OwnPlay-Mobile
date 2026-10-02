package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.assertEquals
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
}
