package app.ownplay.player.ui

internal enum class MobilePrimaryDestination {
    HOME,
    LIVE,
    LIBRARY,
    DOWNLOADS,
}

internal enum class MobileShellDestination {
    HOME,
    LIVE,
    LIBRARY,
    DOWNLOADS,
    MOVIES,
    SERIES,
    SETTINGS,
}

internal val mobilePrimaryNavigationOrder = listOf(
    MobilePrimaryDestination.HOME,
    MobilePrimaryDestination.LIVE,
    MobilePrimaryDestination.LIBRARY,
    MobilePrimaryDestination.DOWNLOADS,
)

internal fun MobileShellDestination.primaryDestination(): MobilePrimaryDestination? = when (this) {
    MobileShellDestination.HOME -> MobilePrimaryDestination.HOME
    MobileShellDestination.LIVE -> MobilePrimaryDestination.LIVE
    MobileShellDestination.LIBRARY,
    MobileShellDestination.MOVIES,
    MobileShellDestination.SERIES,
    -> MobilePrimaryDestination.LIBRARY
    MobileShellDestination.DOWNLOADS -> MobilePrimaryDestination.DOWNLOADS
    MobileShellDestination.SETTINGS -> null
}

private fun MobilePrimaryDestination.asShellDestination(): MobileShellDestination = when (this) {
    MobilePrimaryDestination.HOME -> MobileShellDestination.HOME
    MobilePrimaryDestination.LIVE -> MobileShellDestination.LIVE
    MobilePrimaryDestination.LIBRARY -> MobileShellDestination.LIBRARY
    MobilePrimaryDestination.DOWNLOADS -> MobileShellDestination.DOWNLOADS
}

internal data class MobileShellNavigationState(
    val destination: MobileShellDestination,
    val lastPrimary: MobilePrimaryDestination,
) {
    fun open(target: MobileShellDestination): MobileShellNavigationState {
        val targetPrimary = target.primaryDestination()
        return copy(
            destination = target,
            lastPrimary = targetPrimary ?: lastPrimary,
        )
    }

    fun backTarget(): MobileShellDestination? = when (destination) {
        MobileShellDestination.HOME -> null
        MobileShellDestination.LIVE,
        MobileShellDestination.LIBRARY,
        MobileShellDestination.DOWNLOADS,
        -> MobileShellDestination.HOME
        MobileShellDestination.MOVIES,
        MobileShellDestination.SERIES,
        -> MobileShellDestination.LIBRARY
        MobileShellDestination.SETTINGS -> lastPrimary.asShellDestination()
    }

    companion object {
        fun initial(destination: MobileShellDestination): MobileShellNavigationState {
            val primary = destination.primaryDestination() ?: MobilePrimaryDestination.HOME
            return MobileShellNavigationState(
                destination = destination,
                lastPrimary = primary,
            )
        }
    }
}
