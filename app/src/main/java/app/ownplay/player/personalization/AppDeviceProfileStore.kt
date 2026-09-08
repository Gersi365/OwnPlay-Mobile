package app.ownplay.player.personalization

import android.content.Context
import app.ownplay.player.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class AppInputMode {
    TOUCHSCREEN,
    DPAD,
}

enum class AppDeviceProfile(
    val inputMode: AppInputMode,
) {
    SMARTPHONE(AppInputMode.TOUCHSCREEN),
    ANDROID_TV(AppInputMode.DPAD);

    val usesDpad: Boolean
        get() = inputMode == AppInputMode.DPAD
}

data class AppDeviceSettings(
    val profile: AppDeviceProfile,
) {
    val inputMode: AppInputMode
        get() = profile.inputMode
}

sealed interface AppDeviceProfileSelection {
    data object Loading : AppDeviceProfileSelection
    data class Configured(
        val settings: AppDeviceSettings,
    ) : AppDeviceProfileSelection
}

/**
 * Build-target device profile. Mobile presentation no longer persists or exposes orientation
 * preferences; browsing orientation is owned by PlaybackWindowController policy.
 */
class AppDeviceProfileStore(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {
    fun observeSelection(): Flow<AppDeviceProfileSelection> = flowOf(
        AppDeviceProfileSelection.Configured(
            AppDeviceSettings(profile = buildTargetDeviceProfile()),
        ),
    )
}

private fun buildTargetDeviceProfile(): AppDeviceProfile = if (BuildConfig.IS_TV_BUILD) {
    AppDeviceProfile.ANDROID_TV
} else {
    AppDeviceProfile.SMARTPHONE
}
