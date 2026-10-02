package app.ownplay.mobile.feature.live.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCatchUpPolicyTest {
    @Test
    fun eligibleProgramsKeepsCompletedHistoryWithinProviderWindowAndOrdersRecentFirst() {
        val now = 1_000_000L
        val programs = listOf(
            LiveProgram("Recent", now - 7_200L, now - 3_600L),
            LiveProgram("Too old", now - 4 * 86_400L, now - 4 * 86_400L + 1_800L),
            LiveProgram("Current", now - 600L, now + 600L),
            LiveProgram("Older", now - 14_400L, now - 10_800L),
        )

        val result = LiveCatchUpPolicy.eligiblePrograms(
            channelId = "channel-a",
            programs = programs,
            nowEpochSeconds = now,
            archiveDurationDays = 2,
        )

        assertEquals(listOf("Recent", "Older"), result.map { it.title })
        assertTrue(result.all { it.programId.startsWith("channel-a:") })
    }

    @Test
    fun completedProgressDoesNotExposeResumeOrProgressBar() {
        val base = LiveCatchUpProgram(
            programId = "p",
            title = "Program",
            startEpochSeconds = 100,
            endEpochSeconds = 200,
        )
        val completed = LiveCatchUpPolicy.withProgress(
            program = base,
            positionMs = 100_000,
            durationMs = 100_000,
            completed = true,
        )

        assertNull(LiveCatchUpPolicy.resumablePositionMs(completed))
        assertNull(LiveCatchUpPolicy.progressFraction(completed))
    }

    @Test
    fun partialProgressIsResumableAndClamped() {
        val base = LiveCatchUpProgram(
            programId = "p",
            title = "Program",
            startEpochSeconds = 100,
            endEpochSeconds = 200,
        )
        val partial = LiveCatchUpPolicy.withProgress(
            program = base,
            positionMs = 25_000,
            durationMs = 100_000,
            completed = false,
        )

        assertEquals(25_000L, LiveCatchUpPolicy.resumablePositionMs(partial))
        assertEquals(0.25f, LiveCatchUpPolicy.progressFraction(partial) ?: -1f, 0.0001f)
    }
}
