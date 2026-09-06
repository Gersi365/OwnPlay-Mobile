package app.ownplay.player.epg

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgRefreshGenerationTest {
    @Test
    fun invalidationRejectsOlderRefreshSnapshot() {
        val generation = EpgRefreshGeneration()
        val snapshot = generation.snapshot("source-a")

        generation.invalidate("source-a")

        assertFalse(generation.isCurrent("source-a", snapshot))
        assertTrue(generation.isCurrent("source-a", generation.snapshot("source-a")))
    }

    @Test
    fun beginningRefreshSupersedesOlderSnapshots() {
        val generation = EpgRefreshGeneration()
        val oldSnapshot = generation.snapshot("source-a")

        val refreshSnapshot = generation.beginRefresh("source-a")

        assertFalse(generation.isCurrent("source-a", oldSnapshot))
        assertTrue(generation.isCurrent("source-a", refreshSnapshot))
    }

    @Test
    fun staleSnapshotDoesNotRunPublishAction() {
        val generation = EpgRefreshGeneration()
        val snapshot = generation.snapshot("source-a")
        var published = false

        generation.invalidate("source-a")
        val accepted = generation.runIfCurrent("source-a", snapshot) {
            published = true
        }

        assertFalse(accepted)
        assertFalse(published)
    }

    @Test
    fun currentSnapshotRunsPublishAction() {
        val generation = EpgRefreshGeneration()
        val snapshot = generation.snapshot("source-a")
        var published = false

        val accepted = generation.runIfCurrent("source-a", snapshot) {
            published = true
        }

        assertTrue(accepted)
        assertTrue(published)
    }

    @Test
    fun successfulRefreshPublicationAdvancesGeneration() {
        val generation = EpgRefreshGeneration()
        val refreshSnapshot = generation.beginRefresh("source-a")
        var published = false

        val accepted = generation.runIfCurrentAndAdvance("source-a", refreshSnapshot) {
            published = true
        }

        assertTrue(accepted)
        assertTrue(published)
        assertFalse(generation.isCurrent("source-a", refreshSnapshot))
    }

    @Test
    fun supersededFullRefreshCancelsInsteadOfReturningFailure() {
        val generation = EpgRefreshGeneration()
        val oldRefresh = generation.beginRefresh("source-a")
        var published = false
        generation.beginRefresh("source-a")

        var cancelled = false
        try {
            generation.runIfCurrentAndAdvance("source-a", oldRefresh) {
                published = true
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertFalse(published)
    }

    @Test
    fun invalidatingOneSourceDoesNotInvalidateAnother() {
        val generation = EpgRefreshGeneration()
        val otherSnapshot = generation.snapshot("source-b")

        generation.invalidate("source-a")

        assertTrue(generation.isCurrent("source-b", otherSnapshot))
    }
}
