package app.ownplay.player.ui.view

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentViewModeTest {
    @Test
    fun `legacy view requests always resolve to canonical cards`() {
        ContentViewMode.entries.forEach { legacyMode ->
            assertEquals(
                ContentViewMode.CARDS,
                canonicalContentViewMode(legacyMode),
            )
        }
    }

    @Test
    fun `missing historical preference resolves to canonical cards`() {
        assertEquals(
            ContentViewMode.CARDS,
            canonicalContentViewMode(null),
        )
    }
}
