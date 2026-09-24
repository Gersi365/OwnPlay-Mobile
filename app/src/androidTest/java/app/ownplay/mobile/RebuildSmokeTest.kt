package app.ownplay.mobile

import android.Manifest
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RebuildSmokeTest {
    @Test
    fun applicationPackageIsStable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("app.ownplay.mobile", context.packageName)
    }

    @Test
    fun mainActivityIsPortraitPreferredAcrossRecreation() {
        grantNotificationPermissionIfNeeded()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, MainActivity::class.java)
        @Suppress("DEPRECATION")
        val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getActivityInfo(
                component,
                PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            context.packageManager.getActivityInfo(component, 0)
        }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    Configuration.ORIENTATION_PORTRAIT,
                    activity.resources.configuration.orientation,
                )
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(
                    Configuration.ORIENTATION_PORTRAIT,
                    activity.resources.configuration.orientation,
                )
            }
        }
    }

    @Test
    fun emptyAppLaunchSurvivesActivityRecreation() {
        grantNotificationPermissionIfNeeded()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
        }
    }

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

}
