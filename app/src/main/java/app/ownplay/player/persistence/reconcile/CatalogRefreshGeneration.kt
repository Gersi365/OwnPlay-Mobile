package app.ownplay.player.persistence.reconcile

internal class CatalogGenerationClock(
    initialGeneration: Long = Long.MIN_VALUE,
) {
    private var lastGeneration: Long = initialGeneration

    @Synchronized
    fun next(nowEpochMillis: Long): Long {
        val generation = if (nowEpochMillis > lastGeneration) {
            nowEpochMillis
        } else {
            check(lastGeneration < Long.MAX_VALUE) { "Catalog generation exhausted" }
            lastGeneration + 1L
        }
        lastGeneration = generation
        return generation
    }
}

internal object CatalogRefreshGeneration {
    private val clock = CatalogGenerationClock()

    fun next(): Long = clock.next(System.currentTimeMillis())
}
