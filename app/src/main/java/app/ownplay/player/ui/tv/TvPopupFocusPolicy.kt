package app.ownplay.player.ui.tv

internal enum class TvPopupFocusAction {
    NONE,
    FOCUS_SELECTED_ITEM,
    RESTORE_TRIGGER,
}

internal object TvPopupFocusPolicy {
    fun action(
        enabled: Boolean,
        expanded: Boolean,
        wasExpanded: Boolean,
    ): TvPopupFocusAction = when {
        !enabled -> TvPopupFocusAction.NONE
        expanded -> TvPopupFocusAction.FOCUS_SELECTED_ITEM
        wasExpanded -> TvPopupFocusAction.RESTORE_TRIGGER
        else -> TvPopupFocusAction.NONE
    }
}
