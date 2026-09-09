package app.ownplay.player.ui

internal enum class MobilePrimaryDestination {
    LIVE,
    LIBRARY,
    SETTINGS,
}

internal enum class MobileShellDestination {
    LIVE,
    LIBRARY,
    MOVIES,
    SERIES,
    SETTINGS,
}

internal val mobilePrimaryNavigationOrder = listOf(
    MobilePrimaryDestination.LIVE,
    MobilePrimaryDestination.LIBRARY,
    MobilePrimaryDestination.SETTINGS,
)

internal fun MobileShellDestination.primaryDestination(): MobilePrimaryDestination? = when (this) {
    MobileShellDestination.LIVE -> MobilePrimaryDestination.LIVE
    MobileShellDestination.LIBRARY,
    MobileShellDestination.MOVIES,
    MobileShellDestination.SERIES,
    -> MobilePrimaryDestination.LIBRARY
    MobileShellDestination.SETTINGS -> MobilePrimaryDestination.SETTINGS
}

private fun MobilePrimaryDestination.asShellDestination(): MobileShellDestination = when (this) {
    MobilePrimaryDestination.LIVE -> MobileShellDestination.LIVE
    MobilePrimaryDestination.LIBRARY -> MobileShellDestination.LIBRARY
    MobilePrimaryDestination.SETTINGS -> MobileShellDestination.SETTINGS
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
        MobileShellDestination.LIVE -> null
        MobileShellDestination.LIBRARY,
        MobileShellDestination.SETTINGS,
        -> MobileShellDestination.LIVE
        MobileShellDestination.MOVIES,
        MobileShellDestination.SERIES,
        -> MobileShellDestination.LIBRARY
    }

    companion object {
        fun initial(destination: MobileShellDestination): MobileShellNavigationState {
            val primary = destination.primaryDestination() ?: MobilePrimaryDestination.LIVE
            return MobileShellNavigationState(
                destination = destination,
                lastPrimary = primary,
            )
        }
    }
}
