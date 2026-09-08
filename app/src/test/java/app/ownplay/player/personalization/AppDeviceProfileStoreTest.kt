package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDeviceProfileStoreTest {
    @Test
    fun onlyTvTargetUsesDpad() {
        assertFalse(AppDeviceProfile.SMARTPHONE.usesDpad)
        assertTrue(AppDeviceProfile.ANDROID_TV.usesDpad)
    }

    @Test
    fun deviceSettingsExposeProfileInputModeWithoutOrientationState() {
        assertEquals(
            AppInputMode.TOUCHSCREEN,
            AppDeviceSettings(AppDeviceProfile.SMARTPHONE).inputMode,
        )
        assertEquals(
            AppInputMode.DPAD,
            AppDeviceSettings(AppDeviceProfile.ANDROID_TV).inputMode,
        )
    }
}
