package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileShellNavigationTest {
    @Test
    fun `primary navigation order is Home Live Library Downloads`() {
        assertEquals(
            listOf(
                MobilePrimaryDestination.HOME,
                MobilePrimaryDestination.LIVE,
                MobilePrimaryDestination.LIBRARY,
                MobilePrimaryDestination.DOWNLOADS,
            ),
            mobilePrimaryNavigationOrder,
        )
    }

    @Test
    fun `settings is outside primary navigation`() {
        assertNull(MobileShellDestination.SETTINGS.primaryDestination())
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
    fun `settings round trip returns to exact calling destination`() {
        listOf(
            MobileShellDestination.HOME,
            MobileShellDestination.LIVE,
            MobileShellDestination.LIBRARY,
            MobileShellDestination.DOWNLOADS,
            MobileShellDestination.MOVIES,
            MobileShellDestination.SERIES,
        ).forEach { origin ->
            val settingsState = MobileShellNavigationState
                .initial(origin)
                .open(MobileShellDestination.SETTINGS)

            assertEquals(origin, settingsState.backTarget())
        }
    }

    @Test
    fun `top level media destinations back to Home`() {
        listOf(
            MobileShellDestination.LIVE,
            MobileShellDestination.LIBRARY,
            MobileShellDestination.DOWNLOADS,
        ).forEach { destination ->
            assertEquals(
                MobileShellDestination.HOME,
                MobileShellNavigationState.initial(destination).backTarget(),
            )
        }
        assertNull(MobileShellNavigationState.initial(MobileShellDestination.HOME).backTarget())
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
