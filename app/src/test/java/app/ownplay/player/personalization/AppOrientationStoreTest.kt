package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun onlyTvTargetUsesDpad() {
        assertFalse(AppDeviceProfile.SMARTPHONE.usesDpad)
        assertTrue(AppDeviceProfile.ANDROID_TV.usesDpad)
    }

    @Test
    fun smartphoneRespectsOrientationWhileTvStaysLandscape() {
        assertEquals(
            AppOrientationMode.PORTRAIT,
            AppDeviceSettings(
                profile = AppDeviceProfile.SMARTPHONE,
                smartphoneOrientation = AppOrientationMode.PORTRAIT,
            ).effectiveOrientation,
        )
        assertEquals(
            AppOrientationMode.LANDSCAPE,
            AppDeviceSettings(
                profile = AppDeviceProfile.SMARTPHONE,
                smartphoneOrientation = AppOrientationMode.LANDSCAPE,
            ).effectiveOrientation,
        )
        assertEquals(
            AppOrientationMode.LANDSCAPE,
            AppDeviceSettings(
                profile = AppDeviceProfile.ANDROID_TV,
                smartphoneOrientation = AppOrientationMode.PORTRAIT,
            ).effectiveOrientation,
        )
    }
}
