package app.ownplay.player.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileLegacyPresentationPurgeContractTest {
    @Test
    fun dormantLegacyPresentationFilesAreNotRetained() {
        val removed = listOf(
            "src/main/java/app/ownplay/player/ui/OwnPlayApp.kt",
            "src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryOfflinePresentation.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryPlaybackPresentationSession.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesComponents.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesGrouping.kt",
            "src/main/java/app/ownplay/player/ui/live/PortraitLiveViewModes.kt",
            "src/main/java/app/ownplay/player/ui/view/ContentViewMode.kt",
        )

        removed.forEach { relativePath ->
            assertFalse("Legacy presentation source must stay removed: $relativePath", sourceFile(relativePath).isFile)
        }
    }

    @Test
    fun mobileEntryUsesOnlyCanonicalShellAndLibrary() {
        val target = sourceFile("src/mobile/java/app/ownplay/player/ui/TargetOwnPlayApp.kt").readText()
        val shell = sourceFile("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt").readText()

        assertTrue(target.contains("MobileOwnPlayApp("))
        assertFalse(target.contains("OwnPlayApp("))
        assertTrue(shell.contains("MobileLibraryRoute("))
        assertFalse(shell.contains("UnifiedLibraryRoute"))
        assertFalse(shell.contains("ContentViewMode"))
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
