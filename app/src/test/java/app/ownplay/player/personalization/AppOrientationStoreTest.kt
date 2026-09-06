package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppOrientationStoreTest {
    @Test
    fun missingOrUnknownPreferenceFallsBackToPortrait() {
        assertEquals(AppOrientationMode.PORTRAIT, AppOrientationMode.fromStored(null))
        assertEquals(AppOrientationMode.PORTRAIT, AppOrientationMode.fromStored(""))
        assertEquals(AppOrientationMode.PORTRAIT, AppOrientationMode.fromStored("sensor"))
    }

    @Test
    fun storedLandscapePreferenceRestoresLandscape() {
        assertEquals(
            AppOrientationMode.LANDSCAPE,
            AppOrientationMode.fromStored("landscape"),
        )
    }

    @Test
    fun missingOrUnknownOrientationCanBeDetected() {
        assertNull(AppOrientationMode.fromStoredOrNull(null))
        assertNull(AppOrientationMode.fromStoredOrNull(""))
        assertNull(AppOrientationMode.fromStoredOrNull("sensor"))
    }
}
