package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileLibraryContractTest {
    @Test
    fun mobileShellUsesCanonicalLibraryRoute() {
        val source = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val normalized = normalizedSource(source)

        assertTrue(normalized.contains("MobileSection.LIBRARY -> MobileLibraryRoute("))
        assertFalse(source.contains("UnifiedLibraryRoute"))
    }

    @Test
    fun canonicalLibraryExposesOnlyMoviesAndSeriesPresentation() {
        val source = sourceText("src/mobile/java/app/ownplay/player/ui/library/MobileLibraryRoute.kt")

        assertTrue(source.contains("MobileLibraryFilter.MOVIES"))
        assertTrue(source.contains("MobileLibraryFilter.SERIES"))
        assertTrue(source.contains("GridCells.Adaptive"))
        assertFalse(source.contains("ContentViewMode"))
        assertFalse(source.contains("OfflineDownloadFeatureRuntime"))
        assertFalse(source.contains("offlineOnly"))
        assertFalse(source.contains("UnifiedLibraryFilter.ALL"))
        assertFalse(source.contains("\"Offline\""))
    }

    @Test
    fun canonicalLibraryKeepsContinueWatchingAndDetailRouting() {
        val source = sourceText("src/mobile/java/app/ownplay/player/ui/library/MobileLibraryRoute.kt")

        assertTrue(source.contains("LibraryMovieContinueWatchingStrip("))
        assertTrue(source.contains("LibrarySeriesContinueWatchingStrip("))
        assertTrue(source.contains("onOpenMovieDetails(sourceId, movie.movieId)"))
        assertTrue(source.contains("onOpenSeriesDetails(sourceId, episode.seriesId)"))
    }
}
