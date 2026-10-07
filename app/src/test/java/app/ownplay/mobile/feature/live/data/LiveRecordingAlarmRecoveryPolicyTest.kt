package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecordingAlarmRecoveryPolicyTest {

    @Test
    fun missingPermissionRetainsAStillValidScheduledWindow() {
        assertEquals(
            LiveRecordingAlarmRecoveryDecision.PermissionRequired,
            LiveRecordingAlarmRecoveryPolicy.decide(
                recording = scheduledRecording(),
                nowEpochSeconds = 900L,
                exactAlarmAccess = false,
            ),
        )
    }

    @Test
    fun permissionRestoredBeforeStartRearmsAtTheOriginalStart() {
        assertEquals(
            LiveRecordingAlarmRecoveryDecision.ScheduleAt(1_000L),
            LiveRecordingAlarmRecoveryPolicy.decide(
                recording = scheduledRecording(),
                nowEpochSeconds = 900L,
                exactAlarmAccess = true,
            ),
        )
    }

    @Test
    fun permissionRestoredDuringWindowStartsRecoveryImmediately() {
        assertEquals(
            LiveRecordingAlarmRecoveryDecision.ScheduleAt(1_200L),
            LiveRecordingAlarmRecoveryPolicy.decide(
                recording = scheduledRecording(),
                nowEpochSeconds = 1_200L,
                exactAlarmAccess = true,
            ),
        )
    }

    @Test
    fun expiredRecordingWindowIsTerminalEvenWhilePermissionIsMissing() {
        assertEquals(
            LiveRecordingAlarmRecoveryDecision.Missed,
            LiveRecordingAlarmRecoveryPolicy.decide(
                recording = scheduledRecording(),
                nowEpochSeconds = 2_000L,
                exactAlarmAccess = false,
            ),
        )
    }

    private fun scheduledRecording() = LiveRecording(
        recordingId = "recording",
        sourceId = "source",
        channelId = "channel",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = 1_000L,
        endEpochSeconds = 2_000L,
        status = LiveRecordingStatus.SCHEDULED,
    )

    @Test
    fun missingExactAlarmAccessKeepsRecordingPersistableAndActionable() {
        val waiting = LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(scheduledRecording())
        assertEquals(LiveRecordingStatus.SCHEDULED, waiting.status)
        assertEquals(
            LiveRecordingScheduleStatePolicy.EXACT_ALARM_ACCESS_ERROR,
            waiting.safeError,
        )
        assertEquals(LiveRecordingScheduleArmedStates.NOT_ARMED_PERMISSION, waiting.scheduleArmedState)
        assertEquals(LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING, waiting.failureReasonCode)

        val armed = LiveRecordingScheduleStatePolicy.armed(waiting)
        assertEquals(LiveRecordingStatus.SCHEDULED, armed.status)
        assertEquals(null, armed.safeError)
        assertEquals(LiveRecordingScheduleArmedStates.ARMED, armed.scheduleArmedState)
        assertEquals(null, armed.failureReasonCode)
    }

    @Test
    fun revokedAlarmPermissionIsExplicitlyDistinguishedFromInitiallyMissingPermission() {
        val waiting = LiveRecordingScheduleStatePolicy.waitingForExactAlarmAccess(
            scheduledRecording().copy(scheduleArmedState = LiveRecordingScheduleArmedStates.ARMED),
            permissionWasRevoked = true,
        )

        assertEquals(LiveRecordingScheduleArmedStates.NOT_ARMED_PERMISSION, waiting.scheduleArmedState)
        assertEquals(LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED, waiting.failureReasonCode)
    }

}
