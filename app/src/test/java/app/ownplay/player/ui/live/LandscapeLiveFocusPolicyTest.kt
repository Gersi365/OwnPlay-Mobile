package app.ownplay.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LandscapeLiveFocusPolicyTest {
    @Test
    fun `browser arrows stay native so cards keeps two dimensional dpad navigation`() {
        listOf(
            LandscapeLiveFocusAction.LEFT,
            LandscapeLiveFocusAction.RIGHT,
            LandscapeLiveFocusAction.UP,
            LandscapeLiveFocusAction.DOWN,
        ).forEach { action ->
            assertNull(
                LandscapeLiveFocusPolicy.destination(
                    current = LandscapeLiveFocusZone.BROWSER,
                    action = action,
                ),
            )
        }
    }

    @Test
    fun `epg left returns focus to browser`() {
        assertEquals(
            LandscapeLiveFocusZone.BROWSER,
            LandscapeLiveFocusPolicy.destination(
                current = LandscapeLiveFocusZone.EPG,
                action = LandscapeLiveFocusAction.LEFT,
            ),
        )
    }

    @Test
    fun `epg non left directions remain available to native focus handling`() {
        listOf(
            LandscapeLiveFocusAction.RIGHT,
            LandscapeLiveFocusAction.UP,
            LandscapeLiveFocusAction.DOWN,
            LandscapeLiveFocusAction.BACK,
        ).forEach { action ->
            assertNull(
                LandscapeLiveFocusPolicy.destination(
                    current = LandscapeLiveFocusZone.EPG,
                    action = action,
                ),
            )
        }
    }
}
