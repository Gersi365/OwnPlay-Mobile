package app.ownplay.player.ui.live

internal enum class LiveFullscreenEpgDirection {
    LEFT,
    RIGHT,
}

internal object LiveFullscreenEpgPolicy {
    /** The full-guide affordance occupies the slot immediately after the visible programmes. */
    fun fullGuideIndex(programCount: Int): Int = programCount.coerceAtLeast(0)

    fun canEnterTimeline(programCount: Int): Boolean = programCount > 0

    fun moveSelection(
        currentIndex: Int,
        direction: LiveFullscreenEpgDirection,
        programCount: Int,
    ): Int {
        val maxIndex = fullGuideIndex(programCount)
        val safeCurrent = currentIndex.coerceIn(0, maxIndex)
        return when (direction) {
            LiveFullscreenEpgDirection.LEFT -> (safeCurrent - 1).coerceAtLeast(0)
            LiveFullscreenEpgDirection.RIGHT -> (safeCurrent + 1).coerceAtMost(maxIndex)
        }
    }

    fun isFullGuideSelection(
        selectedIndex: Int,
        programCount: Int,
    ): Boolean = programCount > 0 && selectedIndex == fullGuideIndex(programCount)
}
