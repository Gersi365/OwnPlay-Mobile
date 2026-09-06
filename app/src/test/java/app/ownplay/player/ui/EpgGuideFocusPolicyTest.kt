package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgGuideFocusPolicyTest {
    @Test
    fun `tv focuses current program when available`() {
        val focus = EpgGuideFocusPolicy.initialFocus(
            isTelevision = true,
            loading = false,
            failed = false,
            programCount = 5,
            currentIndex = 3,
        )

        assertEquals(EpgGuideFocusTarget.PROGRAM, focus.target)
        assertEquals(3, focus.programIndex)
    }

    @Test
    fun `tv falls back to first program when current is unavailable`() {
        val focus = EpgGuideFocusPolicy.initialFocus(
            isTelevision = true,
            loading = false,
            failed = false,
            programCount = 4,
            currentIndex = null,
        )

        assertEquals(EpgGuideFocusTarget.PROGRAM, focus.target)
        assertEquals(0, focus.programIndex)
    }

    @Test
    fun `tv falls back to done when guide cannot expose programs`() {
        listOf(
            EpgGuideFocusPolicy.initialFocus(true, loading = true, failed = false, programCount = 4, currentIndex = 2),
            EpgGuideFocusPolicy.initialFocus(true, loading = false, failed = true, programCount = 4, currentIndex = 2),
            EpgGuideFocusPolicy.initialFocus(true, loading = false, failed = false, programCount = 0, currentIndex = null),
        ).forEach { focus ->
            assertEquals(EpgGuideFocusTarget.DONE, focus.target)
            assertNull(focus.programIndex)
        }
    }

    @Test
    fun `non tv does not force focus`() {
        val focus = EpgGuideFocusPolicy.initialFocus(
            isTelevision = false,
            loading = false,
            failed = false,
            programCount = 5,
            currentIndex = 2,
        )

        assertEquals(EpgGuideFocusTarget.NONE, focus.target)
        assertNull(focus.programIndex)
    }
}
