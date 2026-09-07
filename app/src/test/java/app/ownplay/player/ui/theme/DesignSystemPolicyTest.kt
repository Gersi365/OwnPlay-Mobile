package app.ownplay.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemPolicyTest {
    @Test
    fun `ordinary phone widths use at least three poster columns`() {
        assertEquals(3, posterColumnCountForWidthDp(320))
        assertEquals(3, posterColumnCountForWidthDp(360))
        assertEquals(3, posterColumnCountForWidthDp(412))
    }

    @Test
    fun `wider screens increase poster density`() {
        assertEquals(4, posterColumnCountForWidthDp(480))
        assertEquals(5, posterColumnCountForWidthDp(600))
        assertTrue(posterColumnCountForWidthDp(840) >= 6)
    }

    @Test
    fun `invalid width falls back safely`() {
        assertEquals(1, posterColumnCountForWidthDp(0))
        assertEquals(1, posterColumnCountForWidthDp(-1))
    }

    @Test
    fun `motion tokens remain restrained`() {
        assertTrue(OwnPlayMotion.PressMillis in 80..160)
        assertTrue(OwnPlayMotion.PosterCrossfadeMillis in 120..200)
        assertTrue(OwnPlayMotion.ContentTransitionMillis in 160..240)
        assertTrue(OwnPlayMotion.SheetTransitionMillis in 180..280)
    }
}
