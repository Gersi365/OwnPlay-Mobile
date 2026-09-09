package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePresentationLegacyStyleContractTest {
    @Test
    fun `Mobile host and exit confirmation are owned by the vNext presentation system`() {
        val manifest = sourceText("src/main/AndroidManifest.xml")
        val styles = sourceText("src/main/res/values/styles.xml")
        val activity = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")

        assertTrue(manifest.contains("android:theme=\"@style/OwnPlayHostTheme\""))
        assertFalse(manifest.contains("@android:style/Theme.Material.NoActionBar"))
        assertTrue(styles.contains("name=\"OwnPlayHostTheme\""))
        assertTrue(styles.contains("#07070D"))
        assertTrue(styles.contains("#8B5CF6"))
        assertTrue(activity.contains("VNextConfirmationDialog("))
        assertFalse(activity.contains("android.app.AlertDialog"))
        assertFalse(activity.contains("AlertDialog.Builder"))
    }

    @Test
    fun `reachable OwnPlay confirmation and fullscreen chrome uses vNext primitives`() {
        val removal = sourceText(
            "src/main/java/app/ownplay/player/ui/DownloadRemovalConfirmationDialog.kt",
        )
        val epg = sourceText("src/main/java/app/ownplay/player/ui/EpgGuideSheet.kt")
        val playback = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")
        val dialogs = sourceText("src/main/java/app/ownplay/player/ui/VNextModalDialog.kt")

        assertTrue(removal.contains("VNextConfirmationDialog("))
        assertFalse(removal.contains("AlertDialog("))
        assertTrue(epg.contains("VNextModalDialog("))
        assertFalse(epg.contains("AlertDialog("))
        assertTrue(playback.contains("VNextMediaIconAction("))
        assertTrue(playback.contains("SliderDefaults.colors("))
        assertFalse(playback.contains("IconButton("))
        assertTrue(dialogs.contains("Dialog("))
        assertTrue(dialogs.contains("RoundedCornerShape(22.dp)"))
        assertFalse(dialogs.contains("AlertDialog("))
    }
}
