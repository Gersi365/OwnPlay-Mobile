package app.ownplay.mobile.feature.live.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RecordingClockDeadlinePolicyTest {
    private val tirane = ZoneId.of("Europe/Tirane")

    @Test fun explicitNextDateCrossesMidnightOnce() {
        val start = Instant.parse("2026-10-03T21:45:00Z").epochSecond
        val result = RecordingClockDeadlinePolicy.resolve("2026-10-04", "00:30", tirane, start)
        assertEquals(Instant.parse("2026-10-03T22:30:00Z").epochSecond, result.epochSeconds)
    }

    @Test fun pastTimeAndInvalidCalendarDateAreRejected() {
        val start = Instant.parse("2026-10-03T19:00:00Z").epochSecond
        assertEquals(RecordingClockError.NOT_AFTER_START, RecordingClockDeadlinePolicy.resolve("2026-10-03", "20:00", tirane, start).error)
        assertEquals(RecordingClockError.INVALID_DATE_OR_TIME, RecordingClockDeadlinePolicy.resolve("2026-02-30", "21:30", tirane, start).error)
        assertEquals(RecordingClockError.INVALID_DATE_OR_TIME, RecordingClockDeadlinePolicy.resolve("2026-10-04", "24:30", tirane, start).error)
    }

    @Test fun nonexistentAndRepeatedDstClockTimesNeedAnUnambiguousChoice() {
        assertEquals(RecordingClockError.DST_GAP, RecordingClockDeadlinePolicy.resolve("2026-03-29", "02:30", tirane, 1L).error)
        assertEquals(RecordingClockError.DST_OVERLAP, RecordingClockDeadlinePolicy.resolve("2026-10-25", "02:30", tirane, 1L).error)
        assertNotNull(RecordingClockDeadlinePolicy.resolve("2026-10-25", "03:30", tirane, Instant.parse("2026-10-25T00:00:00Z").epochSecond).epochSeconds)
    }

    @Test fun absoluteClockAndEpgEndStayFrozenWhileDurationStartsAtConfirmation() {
        assertEquals(300L, RecordingClockDeadlinePolicy.confirmedEnd(100, 300, 125, LiveRecordingStopPolicy.ABSOLUTE_TIME))
        assertEquals(300L, RecordingClockDeadlinePolicy.confirmedEnd(100, 300, 125, LiveRecordingStopPolicy.PROGRAM_END))
        assertEquals(325L, RecordingClockDeadlinePolicy.confirmedEnd(100, 300, 125, LiveRecordingStopPolicy.DEADLINE))
        org.junit.Assert.assertNull(RecordingClockDeadlinePolicy.confirmedEnd(100, 300, 301, LiveRecordingStopPolicy.ABSOLUTE_TIME))
    }

    @Test fun manualClockUsesTheSameFiveHourBoundAsDuration() {
        val start = Instant.parse("2026-10-03T19:00:00Z").epochSecond
        assertNotNull(RecordingClockDeadlinePolicy.resolve("2026-10-04", "02:00", tirane, start).epochSeconds)
        assertEquals(RecordingClockError.TOO_LONG, RecordingClockDeadlinePolicy.resolve("2026-10-04", "02:01", tirane, start).error)
    }
}
