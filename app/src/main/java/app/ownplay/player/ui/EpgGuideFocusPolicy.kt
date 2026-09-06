package app.ownplay.player.ui

internal enum class EpgGuideFocusTarget {
    NONE,
    PROGRAM,
    DONE,
}

internal data class EpgGuideInitialFocus(
    val target: EpgGuideFocusTarget,
    val programIndex: Int? = null,
)

internal object EpgGuideFocusPolicy {
    fun initialFocus(
        isTelevision: Boolean,
        loading: Boolean,
        failed: Boolean,
        programCount: Int,
        currentIndex: Int?,
    ): EpgGuideInitialFocus {
        if (!isTelevision) {
            return EpgGuideInitialFocus(EpgGuideFocusTarget.NONE)
        }
        if (loading || failed || programCount <= 0) {
            return EpgGuideInitialFocus(EpgGuideFocusTarget.DONE)
        }
        val preferredIndex = currentIndex
            ?.takeIf { it in 0 until programCount }
            ?: 0
        return EpgGuideInitialFocus(
            target = EpgGuideFocusTarget.PROGRAM,
            programIndex = preferredIndex,
        )
    }
}
