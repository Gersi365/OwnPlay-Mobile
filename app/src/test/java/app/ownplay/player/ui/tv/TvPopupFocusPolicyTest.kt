package app.ownplay.player.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPopupFocusPolicyTest {
    @Test
    fun `opening popup focuses the selected item`() {
        assertEquals(
            TvPopupFocusAction.FOCUS_SELECTED_ITEM,
            TvPopupFocusPolicy.action(
                enabled = true,
                expanded = true,
                wasExpanded = false,
            ),
        )
    }

    @Test
    fun `closing an opened popup restores its trigger`() {
        assertEquals(
            TvPopupFocusAction.RESTORE_TRIGGER,
            TvPopupFocusPolicy.action(
                enabled = true,
                expanded = false,
                wasExpanded = true,
            ),
        )
    }

    @Test
    fun `idle and non tv popup states do not force focus`() {
        assertEquals(
            TvPopupFocusAction.NONE,
            TvPopupFocusPolicy.action(
                enabled = true,
                expanded = false,
                wasExpanded = false,
            ),
        )
        assertEquals(
            TvPopupFocusAction.NONE,
            TvPopupFocusPolicy.action(
                enabled = false,
                expanded = true,
                wasExpanded = false,
            ),
        )
    }
}
