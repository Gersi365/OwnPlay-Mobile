package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOnDemandDetailEntryContractTest {
    @Test
    fun `Live cannot open legacy bare Movie or Series catalogs`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val liveEntry = sourceText("src/mobile/java/app/ownplay/player/ui/LiveRoute.kt")
        val liveSurface = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")
        val liveSection = shell
            .substringAfter("MobileSection.LIVE -> {")
            .substringBefore("MobileSection.LIBRARY ->")

        listOf(liveEntry, liveSurface).forEach { source ->
            assertFalse(source.contains("onOpenMovies"))
            assertFalse(source.contains("onOpenSeries"))
        }
        assertFalse(liveSection.contains("onOpenMovies ="))
        assertFalse(liveSection.contains("onOpenSeries ="))
        assertTrue(liveSection.contains("onOpenSettings = ::openSettings"))
    }

    @Test
    fun `Home and Library enter on-demand routes through explicit detail targets`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")

        assertTrue(shell.contains("showMovieDetail("))
        assertTrue(shell.contains("requestedVodMovieId = movieId"))
        assertTrue(shell.contains("openSection(MobileSection.MOVIES)"))
        assertTrue(shell.contains("showSeriesDetail("))
        assertTrue(shell.contains("requestedSeriesId = seriesId"))
        assertTrue(shell.contains("openSection(MobileSection.SERIES)"))
    }

    @Test
    fun `on-demand routes are detail and playback hosts only`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        assertFalse(vod.contains("isLandscape"))
        assertFalse(vod.contains("ORIENTATION_LANDSCAPE"))
        assertFalse(vod.contains("VodSortOrder"))
        assertFalse(vod.contains("MoviesCatalogContent"))
        assertFalse(vod.contains("MovieCategoryRail"))
        assertFalse(vod.contains("showMovieCatalog"))
        assertTrue(vod.contains("MovieDetailsPane("))
        assertTrue(vod.contains("VodPlaybackScreen("))

        assertFalse(series.contains("isLandscape"))
        assertFalse(series.contains("ORIENTATION_LANDSCAPE"))
        assertFalse(series.contains("SeriesCatalogPane"))
        assertFalse(series.contains("showSeriesCatalog"))
        assertTrue(series.contains("SeriesDetailsPane("))
        assertTrue(series.contains("SeriesPlaybackScreen("))
    }
}
