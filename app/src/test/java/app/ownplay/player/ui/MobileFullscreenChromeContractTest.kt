package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileFullscreenChromeContractTest {
    @Test
    fun `playback origin badge cannot render in Mobile builds`() {
        val badge = sourceText("src/main/java/app/ownplay/player/ui/PlaybackOriginBadge.kt")
        val build = sourceText("build.gradle.kts")

        val mobileGuard = badge.indexOf("if (!BuildConfig.IS_TV_BUILD) return")
        val firstSurface = badge.indexOf("Surface(")

        assertTrue(mobileGuard >= 0)
        assertTrue(firstSurface > mobileGuard)
        assertTrue(build.contains("buildConfigField(\"boolean\", \"IS_TV_BUILD\", \"false\")"))
    }
}
