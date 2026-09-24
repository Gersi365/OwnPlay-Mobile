package app.ownplay.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `primary navigation is live library settings`() {
        assertEquals(
            listOf("Live", "Library", "Settings"),
            AppDestination.primaryEntries.map { it.label },
        )
    }

    @Test
    fun `primary roots require exit confirmation`() {
        assertEquals(AppRootBackAction.CONFIRM_EXIT, AppDestination.LIVE.rootBackAction)
        assertEquals(AppRootBackAction.CONFIRM_EXIT, AppDestination.LIBRARY.rootBackAction)
        assertEquals(AppRootBackAction.CONFIRM_EXIT, AppDestination.SETTINGS.rootBackAction)
    }

    @Test
    fun `downloads remains a stable non-primary destination`() {
        assertFalse(AppDestination.DOWNLOADS.primary)
        assertEquals("Downloads", AppDestination.DOWNLOADS.label)
        assertEquals(AppDestination.LIBRARY, AppDestination.DOWNLOADS.bottomNavigationSelection)
        assertEquals(AppRootBackAction.RETURN_TO_PRIMARY, AppDestination.DOWNLOADS.rootBackAction)
    }
}
