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
            "src/main/java/app/ownplay/player/ui/OrientationSetupScreen.kt",
            "src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryOfflinePresentation.kt",
            "src/main/java/app/ownplay/player/ui/library/LibraryPlaybackPresentationSession.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesComponents.kt",
            "src/main/java/app/ownplay/player/ui/library/LibrarySeriesGrouping.kt",
            "src/main/java/app/ownplay/player/ui/live/PortraitLiveViewModes.kt",
            "src/main/java/app/ownplay/player/ui/live/LiveBrowseHierarchy.kt",
            "src/main/java/app/ownplay/player/ui/live/LiveBrowseScreen.kt",
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
        assertFalse(Regex("(?m)^\\s*OwnPlayApp\\(").containsMatchIn(target))
        assertTrue(shell.contains("MobileLibraryRoute("))
        assertFalse(shell.contains("UnifiedLibraryRoute"))
        assertFalse(shell.contains("ContentViewMode"))
    }

    @Test
    fun startupAndLiveManagementUseCanonicalMobilePresentation() {
        val activity = sourceFile("src/main/java/app/ownplay/player/MainActivity.kt").readText()
        val management = sourceFile("src/main/java/app/ownplay/player/ui/LiveManagementScreen.kt").readText()

        assertTrue(activity.contains("MobileStartupLoadingSurface()"))
        assertFalse(activity.contains("OrientationSetupLoadingSurface"))
        assertTrue(management.contains("Visible Categories"))
        assertTrue(management.contains("Hidden Categories"))
        assertTrue(management.contains("Custom Groups"))
        assertTrue(management.contains("Visible Channels"))
        assertTrue(management.contains("Hidden Channels"))
        assertFalse(management.contains("LiveBrowseScreen("))
        assertFalse(management.contains("LocalConfiguration"))
        assertFalse(management.contains("UI_MODE_TYPE_TELEVISION"))
    }

    @Test
    fun onDemandRoutesDoNotReintroduceAlternateCatalogPresentation() {
        val vod = sourceFile("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt").readText()
        val series = sourceFile("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt").readText()

        listOf(vod, series).forEach { source ->
            assertFalse(source.contains("ORIENTATION_LANDSCAPE"))
            assertFalse(source.contains("isLandscape"))
            assertFalse(source.contains("LocalConfiguration"))
        }
        assertFalse(vod.contains("MoviesCatalogContent"))
        assertFalse(vod.contains("MovieCategoryRail"))
        assertFalse(series.contains("SeriesCatalogPane"))
    }

    private fun sourceFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.exists()) return direct
        return File("app/$relativePath")
    }
}
