package app.ownplay.player.ui

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandDetailsPortraitContractTest {
    @Test
    fun `movie details are artwork led while preserving canonical actions`() {
        val movie = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/vod/MovieDetailsPane.kt"),
        )
        val posterIndex = movie.indexOf("RemotePoster(")
        val titleHierarchyIndex = movie.indexOf("MaterialTheme.typography.headlineMedium")

        assertTrue(posterIndex >= 0)
        assertTrue(titleHierarchyIndex > posterIndex)
        assertTrue(movie.contains("width(188.dp)"))
        assertTrue(movie.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue(movie.contains("label = \"Play from beginning\""))
        assertTrue(movie.contains("Downloaded · OwnPlay Downloads"))
        assertTrue(movie.contains("Saving to OwnPlay Downloads"))
        assertTrue(movie.contains("MovieDownloadContext("))
        assertFalse(movie.contains("text = \"About\""))
    }

    @Test
    fun `series details keep inline seasons and separate browsing rows from episode actions`() {
        val details = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/series/SeriesDetailsPane.kt"),
        )
        val route = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        assertTrue(details.contains("SeriesSeasonSelector("))
        assertTrue(details.contains("onEpisodeSelected: (seasonNumber: Int, episodeId: String) -> Unit"))
        assertTrue(details.contains("onEpisodeSelected(episode.seasonNumber, episode.episodeId)"))
        assertTrue(details.contains("EpisodeCatalogRow("))
        assertTrue(details.contains("EpisodeDetailActions("))
        assertTrue(details.contains("latestResumeEpisode(loaded)"))
        assertFalse(details.contains("SeriesSeasonRow("))
        assertFalse(details.contains("SeriesSeasonHeader("))
        assertFalse(details.contains("private fun EpisodeRow("))

        assertTrue(route.contains("onEpisodeSelected = { seasonNumber, episodeId ->"))
        assertTrue(route.contains("updateSeriesSelection(seasonNumber, episodeId)"))
        assertFalse(route.contains("selectedSeasonNumber != null ->"))
    }

    @Test
    fun `series summary stays poster led without a separate about card`() {
        val summary = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesInfoSummary.kt")

        assertTrue(summary.contains("RemotePoster("))
        assertTrue(summary.contains("width(132.dp)"))
        assertTrue(summary.contains("details.description"))
        assertTrue(summary.contains("OwnPlayMediaLayout.PosterAspectRatio"))
        assertFalse(summary.contains("text = \"About\""))
        assertFalse(summary.contains("Surface("))
    }
}
