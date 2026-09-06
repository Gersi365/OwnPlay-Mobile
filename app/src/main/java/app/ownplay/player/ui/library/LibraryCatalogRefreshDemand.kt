package app.ownplay.player.ui.library

internal enum class LibraryCatalogRefreshTarget {
    MOVIES,
    SERIES,
}

internal class LibraryCatalogRefreshDemand {
    private val claimedTargets = mutableSetOf<LibraryCatalogRefreshTarget>()

    fun claim(target: LibraryCatalogRefreshTarget): Boolean = claimedTargets.add(target)
}
