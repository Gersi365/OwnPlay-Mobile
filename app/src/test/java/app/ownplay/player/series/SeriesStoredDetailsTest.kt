package app.ownplay.player.series

import app.ownplay.player.persistence.series.EpisodeProgressRow
import app.ownplay.player.persistence.series.ProviderSeriesEntity
import app.ownplay.player.persistence.series.ProviderSeriesSeasonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesStoredDetailsTest {
    @Test
    fun catalogRowWithoutStoredSeasonsIsNotTreatedAsDetailsCache() {
        val details = buildStoredSeriesDetails(
            seriesEntity = seriesEntity(),
            seasons = emptyList(),
            episodeRows = emptyList(),
        )

        assertNull(details)
    }

    @Test
    fun storedSeasonsAndEpisodesBuildImmediateDetailsSnapshot() {
        val details = buildStoredSeriesDetails(
            seriesEntity = seriesEntity(),
            seasons = listOf(
                ProviderSeriesSeasonEntity(
                    seasonId = "series-a:season:1",
                    seriesId = "series-a",
                    seasonNumber = 1,
                    name = "Season One",
                    airDate = "2026-01-02",
                    posterRef = "https://example.test/season.jpg",
                ),
            ),
            episodeRows = listOf(
                EpisodeProgressRow(
                    episodeId = "episode-a",
                    seriesId = "series-a",
                    seriesTitle = "Stored Series",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    title = "Episode Two",
                    providerEpisodeId = "42",
                    containerExtension = "mkv",
                    durationSeconds = 3_600L,
                    posterRef = "https://example.test/episode.jpg",
                    positionMs = 12_000L,
                    durationMs = 3_600_000L,
                    progressCompleted = false,
                    progressUpdatedAtEpochMillis = 50_000L,
                ),
            ),
        )

        requireNotNull(details)
        assertEquals("Stored Series", details.series.name)
        assertEquals("Stored description", details.description)
        assertEquals(8.3, details.rating ?: 0.0, 0.0)
        assertEquals(1, details.seasons.size)
        assertEquals("Season One", details.seasons.single().name)
        assertEquals(1, details.seasons.single().episodes.size)
        assertEquals(42, details.seasons.single().episodes.single().providerEpisodeId)
        assertEquals(12_000L, details.seasons.single().episodes.single().positionMs)
        assertEquals(emptyList<String>(), details.backdropUrls)
        assertNull(details.releaseDate)
        assertNull(details.genre)
        assertNull(details.country)
        assertNull(details.director)
        assertNull(details.cast)
    }

    private fun seriesEntity(): ProviderSeriesEntity = ProviderSeriesEntity(
        seriesId = "series-a",
        sourceId = "source-a",
        providerSeriesId = "7",
        providerCategoryKey = "category-a",
        providerName = "Stored Series",
        posterRef = "https://example.test/series.jpg",
        description = "Stored description",
        providerRating = 8.3,
        lastModifiedEpochSeconds = 123L,
        providerOrder = 0L,
        lastSeenGeneration = 1L,
    )
}
