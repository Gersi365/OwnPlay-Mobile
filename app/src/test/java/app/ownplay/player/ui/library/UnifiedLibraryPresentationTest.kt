package app.ownplay.player.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryPresentationTest {
    @Test
    fun `mobile empty library shows loading while initial refresh is pending`() {
        assertTrue(
            shouldShowMobileLibraryInitialLoading(
                offlineOnly = false,
                hasItems = false,
                refreshing = false,
                initialRefreshPending = true,
            ),
        )
    }

    @Test
    fun `mobile empty library shows loading while refresh is running`() {
        assertTrue(
            shouldShowMobileLibraryInitialLoading(
                offlineOnly = false,
                hasItems = false,
                refreshing = true,
                initialRefreshPending = false,
            ),
        )
    }

    @Test
    fun `cached content is not replaced by initial loading surface`() {
        assertFalse(
            shouldShowMobileLibraryInitialLoading(
                offlineOnly = false,
                hasItems = true,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
    }

    @Test
    fun `offline filter does not show initial loading surface`() {
        assertFalse(
            shouldShowMobileLibraryInitialLoading(
                offlineOnly = true,
                hasItems = false,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
    }
}
