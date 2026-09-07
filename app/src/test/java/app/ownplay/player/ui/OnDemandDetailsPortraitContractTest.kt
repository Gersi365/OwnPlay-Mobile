package app.ownplay.player.ui

import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandDetailsPortraitContractTest {
    @Test
    fun `movie details keep one compact portrait presentation`() {
        val movie = sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt")

        assertTrue(movie.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue(movie.contains("Text(\"Play from beginning\")"))
        assertTrue(movie.contains("Downloaded · OwnPlay Downloads"))
        assertTrue(movie.contains("Saving to OwnPlay Downloads"))
        assertFalse(movie.contains("text = \"About\""))
        assertFalse(movie.contains("Icons.Filled.DownloadDone"))
    }

    @Test
    fun `series details use inline season selector instead of season navigation level`() {
        val details = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt")
        val route = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        assertTrue(details.contains("SeriesSeasonSelector("))
        assertTrue(details.contains("onEpisodeSelected: (seasonNumber: Int, episodeId: String) -> Unit"))
        assertTrue(details.contains("onEpisodeSelected(episode.seasonNumber, episode.episodeId)"))
        assertFalse(details.contains("SeriesSeasonRow("))
        assertFalse(details.contains("SeriesSeasonHeader("))

        assertTrue(route.contains("onEpisodeSelected = { seasonNumber, episodeId ->"))
        assertTrue(route.contains("updateSeriesSelection(seasonNumber, episodeId)"))
        assertFalse(route.contains("selectedSeasonNumber != null ->"))
    }

    @Test
    fun `series summary stays poster led without a separate about card`() {
        val summary = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesInfoSummary.kt")

        assertTrue(summary.contains("RemotePoster("))
        assertTrue(summary.contains("details.description"))
        assertFalse(summary.contains("text = \"About\""))
        assertFalse(summary.contains("Surface("))
    }
}
