package app.ownplay.player.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLibraryPresentationTest {
    @Test
    fun `mobile empty library shows loading while initial refresh is pending`() {
        assertTrue(
            shouldShowMobileLibraryInitialLoading(
                isTelevision = false,
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
                isTelevision = false,
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
                isTelevision = false,
                offlineOnly = false,
                hasItems = true,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
    }

    @Test
    fun `tv and offline filters keep their existing presentation`() {
        assertFalse(
            shouldShowMobileLibraryInitialLoading(
                isTelevision = true,
                offlineOnly = false,
                hasItems = false,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
        assertFalse(
            shouldShowMobileLibraryInitialLoading(
                isTelevision = false,
                offlineOnly = true,
                hasItems = false,
                refreshing = true,
                initialRefreshPending = true,
            ),
        )
    }
}
