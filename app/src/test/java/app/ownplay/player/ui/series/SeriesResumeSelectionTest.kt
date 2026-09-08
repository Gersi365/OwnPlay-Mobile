package app.ownplay.player.ui.series

import app.ownplay.player.series.SeriesDetails
import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.series.SeriesSeason
import app.ownplay.player.series.SeriesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesResumeSelectionTest {
    @Test
    fun `latest incomplete progress wins regardless of season order`() {
        val older = episode(
            id = "older",
            season = 4,
            number = 8,
            positionMs = 15_000L,
            updatedAt = 100L,
        )
        val latest = episode(
            id = "latest",
            season = 2,
            number = 5,
            positionMs = 25_000L,
            updatedAt = 200L,
        )

        assertEquals("latest", latestResumeEpisode(details(older, latest))?.episodeId)
    }

    @Test
    fun `equal progress timestamps use stable season and episode tie break`() {
        val first = episode(
            id = "s2e4",
            season = 2,
            number = 4,
            positionMs = 10_000L,
            updatedAt = 200L,
        )
        val expected = episode(
            id = "s2e5",
            season = 2,
            number = 5,
            positionMs = 10_000L,
            updatedAt = 200L,
        )

        assertEquals("s2e5", latestResumeEpisode(details(first, expected))?.episodeId)
    }

    @Test
    fun `completed and untouched episodes are not resume candidates`() {
        val completed = episode(
            id = "completed",
            season = 1,
            number = 1,
            positionMs = 50_000L,
            updatedAt = 300L,
            completed = true,
        )
        val untouched = episode(
            id = "untouched",
            season = 1,
            number = 2,
            positionMs = 0L,
            updatedAt = 400L,
        )

        assertNull(latestResumeEpisode(details(completed, untouched)))
    }

    private fun details(vararg episodes: SeriesEpisode): SeriesDetails = SeriesDetails(
        series = SeriesSummary(
            seriesId = "series-1",
            providerSeriesId = 1,
            categoryKey = "category",
            name = "Series",
            posterUrl = null,
            description = null,
            rating = null,
            lastModifiedEpochSeconds = null,
            isFavorite = false,
        ),
        description = null,
        posterUrl = null,
        backdropUrls = emptyList(),
        releaseDate = null,
        genre = null,
        country = null,
        director = null,
        cast = null,
        rating = null,
        seasons = episodes
            .groupBy(SeriesEpisode::seasonNumber)
            .map { (seasonNumber, seasonEpisodes) ->
                SeriesSeason(
                    seasonId = "season-$seasonNumber",
                    seasonNumber = seasonNumber,
                    name = null,
                    airDate = null,
                    posterUrl = null,
                    episodes = seasonEpisodes,
                )
            },
    )

    private fun episode(
        id: String,
        season: Int,
        number: Int,
        positionMs: Long,
        updatedAt: Long,
        completed: Boolean = false,
    ): SeriesEpisode = SeriesEpisode(
        episodeId = id,
        seriesId = "series-1",
        seriesTitle = "Series",
        providerEpisodeId = number,
        seasonNumber = season,
        episodeNumber = number,
        title = id,
        containerExtension = "mp4",
        durationSeconds = 1_800L,
        posterUrl = null,
        positionMs = positionMs,
        durationMs = 1_800_000L,
        progressCompleted = completed,
        progressUpdatedAtEpochMillis = updatedAt,
    )
}
