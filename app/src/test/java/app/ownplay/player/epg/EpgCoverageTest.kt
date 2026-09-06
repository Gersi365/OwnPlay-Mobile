package app.ownplay.player.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgCoverageTest {
    @Test
    fun sharedEpgIdCountsEveryOwnPlayChannelOnce() {
        val count = EpgCoverage.matchedChannelCount(
            channelIdsByEpgChannelId = mapOf(
                "shared" to listOf("channel-a", "channel-b"),
                "other" to listOf("channel-c"),
            ),
            programsByEpgChannelId = mapOf(
                "shared" to listOf(program("News")),
                "other" to listOf(program("Sports")),
            ),
        )

        assertEquals(3, count)
    }

    @Test
    fun unmatchedEpgIdsAndDuplicateChannelMappingsDoNotInflateCoverage() {
        val count = EpgCoverage.matchedChannelCount(
            channelIdsByEpgChannelId = mapOf(
                "known" to listOf("channel-a", "channel-a"),
            ),
            programsByEpgChannelId = mapOf(
                "known" to listOf(program("Known")),
                "orphan" to listOf(program("Orphan")),
            ),
        )

        assertEquals(1, count)
    }

    private fun program(title: String) = EpgProgram(
        title = title,
        description = null,
        startEpochSeconds = 100L,
        endEpochSeconds = 200L,
        startLabel = null,
        endLabel = null,
    )
}
