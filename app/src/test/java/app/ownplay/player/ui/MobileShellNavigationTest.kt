package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileShellNavigationTest {
    @Test
    fun `primary navigation order is Live Library Settings`() {
        assertEquals(
            listOf(
                MobilePrimaryDestination.LIVE,
                MobilePrimaryDestination.LIBRARY,
                MobilePrimaryDestination.SETTINGS,
            ),
            mobilePrimaryNavigationOrder,
        )
    }

    @Test
    fun `settings is a primary destination`() {
        assertEquals(
            MobilePrimaryDestination.SETTINGS,
            MobileShellDestination.SETTINGS.primaryDestination(),
        )
    }

    @Test
    fun `movie and series routes remain under Library`() {
        assertEquals(
            MobilePrimaryDestination.LIBRARY,
            MobileShellDestination.MOVIES.primaryDestination(),
        )
        assertEquals(
            MobilePrimaryDestination.LIBRARY,
            MobileShellDestination.SERIES.primaryDestination(),
        )
    }

    @Test
    fun `Library and Settings roots back to Live`() {
        listOf(
            MobileShellDestination.LIBRARY,
            MobileShellDestination.SETTINGS,
        ).forEach { destination ->
            assertEquals(
                MobileShellDestination.LIVE,
                MobileShellNavigationState.initial(destination).backTarget(),
            )
        }
        assertNull(MobileShellNavigationState.initial(MobileShellDestination.LIVE).backTarget())
    }

    @Test
    fun `internal catalog routes back to Library`() {
        assertEquals(
            MobileShellDestination.LIBRARY,
            MobileShellNavigationState.initial(MobileShellDestination.MOVIES).backTarget(),
        )
        assertEquals(
            MobileShellDestination.LIBRARY,
            MobileShellNavigationState.initial(MobileShellDestination.SERIES).backTarget(),
        )
    }
}
