package app.ownplay.player.ui.live

internal object LiveFullscreenEpgPolicy {
    /** The full-guide affordance occupies the slot immediately after the visible programmes. */
    fun fullGuideIndex(programCount: Int): Int = programCount.coerceAtLeast(0)

    fun canEnterTimeline(programCount: Int): Boolean = programCount > 0
}
