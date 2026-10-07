package app.ownplay.mobile.feature.live.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class RecordingClockError { INVALID_DATE_OR_TIME, DST_GAP, DST_OVERLAP, NOT_AFTER_START, TOO_LONG }
data class RecordingClockDeadline(val epochSeconds: Long? = null, val error: RecordingClockError? = null)

object RecordingClockDeadlinePolicy {
    fun resolve(date: String, time: String, zone: ZoneId, startEpochSeconds: Long): RecordingClockDeadline {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) || !Regex("\\d{2}:\\d{2}").matches(time)) {
            return RecordingClockDeadline(error = RecordingClockError.INVALID_DATE_OR_TIME)
        }
        val local = runCatching { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)) }
            .getOrElse { return RecordingClockDeadline(error = RecordingClockError.INVALID_DATE_OR_TIME) }
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return RecordingClockDeadline(error = RecordingClockError.DST_GAP)
        if (offsets.size != 1) return RecordingClockDeadline(error = RecordingClockError.DST_OVERLAP)
        val deadline = local.toEpochSecond(offsets.single())
        return if (deadline <= startEpochSeconds) RecordingClockDeadline(error = RecordingClockError.NOT_AFTER_START)
        else if (deadline - startEpochSeconds > 300L * 60L) RecordingClockDeadline(error = RecordingClockError.TOO_LONG)
        else RecordingClockDeadline(epochSeconds = deadline)
    }

    fun confirmedEnd(requestedStart: Long, requestedEnd: Long, confirmedAt: Long, policy: LiveRecordingStopPolicy): Long? {
        if (requestedEnd <= requestedStart || requestedEnd <= confirmedAt) return null
        return if (policy == LiveRecordingStopPolicy.DEADLINE && requestedStart <= confirmedAt) {
            confirmedAt + (requestedEnd - requestedStart)
        } else requestedEnd
    }
}
