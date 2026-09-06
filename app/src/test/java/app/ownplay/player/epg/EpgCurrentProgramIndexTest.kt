package app.ownplay.player.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class EpgCurrentProgramIndexTest {
    @Test
    fun currentProgramMapsToEveryChannelSharingTheSameEpgId() {
        val current = program("Current", 100, 200)

        val result = EpgCurrentProgramIndex.currentByChannel(
            channelIdsByEpgChannelId = mapOf("epg-1" to listOf("channel-a", "channel-b")),
            programsByEpgChannelId = mapOf("epg-1" to listOf(current)),
            nowEpochSeconds = 150,
        )

        assertSame(current, result["channel-a"])
        assertSame(current, result["channel-b"])
    }

    @Test
    fun overlappingProgramsPreferTheOneThatStartedMostRecently() {
        val older = program("Older overlap", 100, 300)
        val newer = program("Newer overlap", 200, 280)

        assertSame(
            newer,
            EpgCurrentProgramIndex.currentProgram(
                programs = listOf(older, newer),
                nowEpochSeconds = 250,
            ),
        )
    }

    @Test
    fun endBoundaryFutureAndIncompleteProgramsAreNotCurrent() {
        val result = EpgCurrentProgramIndex.currentByChannel(
            channelIdsByEpgChannelId = mapOf(
                "ended" to listOf("channel-ended"),
                "future" to listOf("channel-future"),
                "incomplete" to listOf("channel-incomplete"),
                "active" to listOf("channel-active"),
            ),
            programsByEpgChannelId = mapOf(
                "ended" to listOf(program("Ended", 100, 150)),
                "future" to listOf(program("Future", 151, 200)),
                "incomplete" to listOf(program("Incomplete", null, 200)),
                "active" to listOf(program("Active", 150, 151)),
            ),
            nowEpochSeconds = 150,
        )

        assertFalse(result.containsKey("channel-ended"))
        assertFalse(result.containsKey("channel-future"))
        assertFalse(result.containsKey("channel-incomplete"))
        assertEquals("Active", result["channel-active"]?.title)
    }

    @Test
    fun directCurrentProgramLookupUsesTheSameHalfOpenBoundary() {
        val current = program("Current", 100, 200)
        val next = program("Next", 200, 300)

        assertSame(
            current,
            EpgCurrentProgramIndex.currentProgram(
                programs = listOf(current, next),
                nowEpochSeconds = 199,
            ),
        )
        assertSame(
            next,
            EpgCurrentProgramIndex.currentProgram(
                programs = listOf(current, next),
                nowEpochSeconds = 200,
            ),
        )
    }

    private fun program(
        title: String,
        start: Long?,
        end: Long?,
    ) = EpgProgram(
        title = title,
        description = null,
        startEpochSeconds = start,
        endEpochSeconds = end,
        startLabel = start?.toString(),
        endLabel = end?.toString(),
    )
}
