package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineMediaTvFocusPolicyTest {
    @Test
    fun `remembered visible item wins`() {
        assertEquals(
            "movie-b",
            OfflineMediaTvFocusPolicy.preferredVisibleKey(
                visibleKeys = listOf("movie-a", "movie-b"),
                rememberedKey = "movie-b",
            ),
        )
    }

    @Test
    fun `hidden remembered item falls back to first visible item`() {
        assertEquals(
            "movie-a",
            OfflineMediaTvFocusPolicy.preferredVisibleKey(
                visibleKeys = listOf("movie-a", "series-a"),
                rememberedKey = "missing",
            ),
        )
    }

    @Test
    fun `empty media set has no focus target`() {
        assertNull(
            OfflineMediaTvFocusPolicy.preferredVisibleKey(
                visibleKeys = emptyList(),
                rememberedKey = "old",
            ),
        )
    }
}
