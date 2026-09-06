package app.ownplay.player.epg

import kotlinx.coroutines.CancellationException

internal class EpgRefreshSupersededCancellation(sourceId: String) :
    CancellationException("EPG refresh superseded for source $sourceId")

internal class EpgRefreshGeneration {
    private val generations = mutableMapOf<String, Long>()

    @Synchronized
    fun snapshot(sourceId: String): Long = generations[sourceId] ?: 0L

    @Synchronized
    fun beginRefresh(sourceId: String): Long {
        val next = (generations[sourceId] ?: 0L) + 1L
        generations[sourceId] = next
        return next
    }

    @Synchronized
    fun invalidate(sourceId: String) {
        generations[sourceId] = (generations[sourceId] ?: 0L) + 1L
    }

    @Synchronized
    fun isCurrent(sourceId: String, snapshot: Long): Boolean =
        (generations[sourceId] ?: 0L) == snapshot

    @Synchronized
    fun runIfCurrent(
        sourceId: String,
        snapshot: Long,
        action: () -> Unit,
    ): Boolean {
        if ((generations[sourceId] ?: 0L) != snapshot) return false
        action()
        return true
    }

    @Synchronized
    fun runIfCurrentAndAdvance(
        sourceId: String,
        snapshot: Long,
        action: () -> Unit,
    ): Boolean {
        if ((generations[sourceId] ?: 0L) != snapshot) {
            // Full-source EPG refresh is published through a caller that treats null as a real
            // refresh failure. Cancelling a superseded refresh prevents an older task from
            // regressing a newer Ready state to EpgFailed after losing the generation race.
            throw EpgRefreshSupersededCancellation(sourceId)
        }
        action()
        generations[sourceId] = snapshot + 1L
        return true
    }
}
