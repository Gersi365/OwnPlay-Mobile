package app.ownplay.player.ui.library

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPortraitPresentationContractTest {
    @Test
    fun `Library exposes Movies and Series only while Downloads stays primary navigation`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )

        assertTrue(route.contains("items = libraryCatalogSections"))
        assertTrue(route.contains("UnifiedLibraryFilter.MOVIES -> \"Movies\""))
        assertTrue(route.contains("UnifiedLibraryFilter.SERIES -> \"Series\""))
        assertFalse(route.contains("UnifiedLibraryFilter.OFFLINE"))
        assertFalse(route.contains("UnifiedLibraryFilter.OFFLINE -> \"Downloads\""))
    }

    @Test
    fun `Library catalog uses shared poster density tokens`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )

        assertTrue(
            route.contains(
                "GridCells.Adaptive(minSize = OwnPlayMediaLayout.MinimumPosterWidthDp.dp)",
            ),
        )
        assertTrue(
            route.contains(
                "Arrangement.spacedBy(OwnPlayMediaLayout.GridGapDp.dp)",
            ),
        )
        assertTrue(route.contains("aspectRatio(OwnPlayMediaLayout.PosterAspectRatio)"))
    }

    @Test
    fun `Library cards are poster first and transfer management is not embedded in grid cards`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )

        assertTrue(route.contains("private fun UnifiedMovieCard("))
        assertTrue(route.contains("private fun UnifiedSeriesCard("))
        assertTrue(route.contains("Available offline"))
        assertFalse(route.contains("MovieDownloadActions("))
        assertFalse(route.contains("OfflineOnlyMovieCard("))
        assertFalse(route.contains("Offline episodes"))
    }

    @Test
    fun `unified Continue Watching remains available after Library browsing controls`() {
        val route = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/UnifiedLibraryRoute.kt"),
        )
        val sectionControlsIndex = route.indexOf("items = libraryCatalogSections")
        val continueWatchingIndex = route.indexOf("LibraryUnifiedContinueWatchingStrip(")

        assertTrue(sectionControlsIndex >= 0)
        assertTrue(continueWatchingIndex > sectionControlsIndex)
        assertFalse(route.contains("LibraryMovieContinueWatchingStrip("))
        assertFalse(route.contains("LibrarySeriesContinueWatchingStrip("))
    }
}
