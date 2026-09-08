package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileOnDemandDetailEntryContractTest {
    @Test
    fun `Live cannot open legacy bare Movie or Series catalogs`() {
        val liveEntry = sourceText("src/mobile/java/app/ownplay/player/ui/LiveRoute.kt")
        val liveSurface = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")

        assertTrue(liveEntry.contains("onOpenMovies = {},"))
        assertTrue(liveEntry.contains("onOpenSeries = {},"))
        assertFalse(liveSurface.contains("onOpenMovies()"))
        assertFalse(liveSurface.contains("onOpenSeries()"))
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
}
