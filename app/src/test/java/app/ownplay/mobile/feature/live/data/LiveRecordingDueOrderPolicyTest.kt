package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecordingDueOrderPolicyTest {
    @Test
    fun simultaneousDueRowsUseScheduledTimestampThenRecordingId() {
        val start = 1_000L
        val nowMs = start * 1_000L
        val rows = listOf(
            recording("z", start, scheduledAt = 50L),
            recording("b", start, scheduledAt = 20L),
            recording("a", start, scheduledAt = 20L),
        )

        assertEquals("a", LiveRecordingDueOrderPolicy.firstDue(rows, nowMs)?.recordingId)
    }

    @Test
    fun equalDueStartAndScheduledTimeUseRecordingId() {
        val start = 1_000L
        val nowMs = start * 1_000L

        assertEquals(
            "a",
            LiveRecordingDueOrderPolicy.firstDue(
            listOf(recording("b", start, null), recording("a", start, null)),
                nowMs,
            )?.recordingId,
        )
    }

    private fun recording(id: String, start: Long, scheduledAt: Long?) = LiveRecording(
        recordingId = id,
        sourceId = "source-$id",
        channelId = "channel",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = start,
        endEpochSeconds = start + 100L,
        status = LiveRecordingStatus.SCHEDULED,
        scheduledAtEpochMs = scheduledAt,
    )
}
