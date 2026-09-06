package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePortraitFoundationContractTest {
    @Test
    fun `active Mobile shell has no rotation driven browsing presentation`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        assertFalse(shell.contains("rotationFullscreenEnabled"))
        assertFalse(shell.contains("liveRotationFullscreenEnabled"))
        assertFalse(shell.contains("ORIENTATION_LANDSCAPE"))
    }

    @Test
    fun `active Live browsing has one portrait presentation path`() {
        val live = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")
        assertFalse(live.contains("ORIENTATION_LANDSCAPE"))
        assertFalse(live.contains("isLandscape"))
        assertFalse(live.contains("LocalConfiguration"))
        assertTrue(live.contains("LivePreviewPanel("))
        assertTrue(live.contains("EpgPanel("))
        assertTrue(live.contains("MobileLiveBrowsePane("))
    }

    @Test
    fun `Settings does not expose orientation or Interface controls`() {
        val screen = sourceText("src/main/java/app/ownplay/player/ui/SettingsScreen.kt")
        val menu = sourceText("src/main/java/app/ownplay/player/ui/SettingsPortrait.kt")
        val settings = screen + menu
        assertFalse(settings.contains("AppOrientationStore"))
        assertFalse(settings.contains("AppOrientationMode"))
        assertFalse(settings.contains("OrientationButton"))
        assertFalse(settings.contains("title = \"Interface\""))
    }

    @Test
    fun `alternate content presentation selector is hidden and canonicalized to Cards`() {
        val viewMode = sourceText("src/main/java/app/ownplay/player/ui/view/ContentViewMode.kt")
        assertTrue(viewMode.contains("canonicalContentViewMode"))
        assertTrue(viewMode.contains("ContentViewMode.CARDS"))
        assertTrue(viewMode.contains("fun ContentViewModeMenu("))
        assertTrue(viewMode.contains(") = Unit"))
        assertFalse(viewMode.contains("\"List\""))
        assertFalse(viewMode.contains("\"Compact\""))
        assertFalse(viewMode.contains("\"Gallery\""))
    }

    @Test
    fun `Library retains Offline without exposing a global All label`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
        assertTrue(library.contains("label = \"Offline\""))
        assertFalse(library.contains("\"All\""))
    }
}
