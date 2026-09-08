package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePortraitFoundationContractTest {
    @Test
    fun `active Mobile entry point pins browsing configuration to portrait`() {
        val target = sourceText("src/mobile/java/app/ownplay/player/ui/TargetOwnPlayApp.kt")
        assertTrue(target.contains("orientation = Configuration.ORIENTATION_PORTRAIT"))
        assertTrue(target.contains("LocalConfiguration provides portraitConfiguration"))
    }

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
    fun `Library uses one canonical dense poster presentation path`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
        assertFalse(library.contains("ContentViewMode"))
        assertFalse(library.contains("viewMode ="))
        assertFalse(library.contains("LazyColumn("))
        assertFalse(library.contains("CompactMovieCard("))
        assertFalse(library.contains("CompactOfflineMovieCard("))
        assertFalse(library.contains("CompactSeriesCard("))
        assertFalse(library.contains("CompactOfflineSeriesCard("))
        assertFalse(library.contains("MovieListRow("))
        assertFalse(library.contains("OfflineMovieListRow("))
        assertFalse(library.contains("SeriesListRow("))
        assertFalse(library.contains("OfflineSeriesListRow("))
        assertFalse(library.contains("compact ="))
        assertTrue(library.contains("LazyVerticalGrid("))
        assertTrue(
            library.contains(
                "GridCells.Adaptive(minSize = OwnPlayMediaLayout.MinimumPosterWidthDp.dp)",
            ),
        )
        assertTrue(library.contains("OwnPlayMediaLayout.GridGapDp.dp"))
    }

    @Test
    fun `Library keeps Movies and Series without a global All or Downloads filter`() {
        val library = sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt")
        assertTrue(library.contains("UnifiedLibraryFilter.MOVIES"))
        assertTrue(library.contains("UnifiedLibraryFilter.SERIES"))
        assertFalse(library.contains("UnifiedLibraryFilter.OFFLINE"))
        assertFalse(library.contains("Text(\"All\")"))
    }
}
