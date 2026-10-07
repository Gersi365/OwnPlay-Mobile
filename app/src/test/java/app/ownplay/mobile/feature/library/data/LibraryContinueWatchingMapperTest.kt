package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.LibraryEpisodeProgressRow
import app.ownplay.mobile.data.db.LibraryMovieProgressRow
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryContinueWatchingMapperTest {
    @Test
    fun continueWatchingKeepsOnlyMostRecentEpisodePerSeriesAndOrdersByActivity() {
        val items = LibraryCatalogMapper.continueWatching(
            movieRows = listOf(
                LibraryMovieProgressRow(
                    contentId = "movie-a",
                    title = "Movie A",
                    posterUrl = null,
                    positionMs = 10_000L,
                    durationMs = 100_000L,
                    updatedAt = 150L,
                ),
            ),
            episodeRows = listOf(
                episode("episode-old", "series-a", 100L),
                episode("episode-new", "series-a", 200L),
                episode("episode-b", "series-b", 175L),
            ),
        )

        assertEquals(
            listOf("episode-new", "episode-b", "movie-a"),
            items.map { it.contentId },
        )
        assertEquals("series-a", items.first().seriesId)
    }

    @Test
    fun continueWatchingRequiresOneMinuteOrTwoPercent() {
        val items = LibraryCatalogMapper.continueWatching(
            movieRows = listOf(
                LibraryMovieProgressRow(
                    "too-early",
                    "Too Early",
                    null,
                    10_000L,
                    1_000_000L,
                    100L,
                ),
                LibraryMovieProgressRow(
                    "by-percent",
                    "By Percent",
                    null,
                    20_000L,
                    1_000_000L,
                    101L,
                ),
                LibraryMovieProgressRow(
                    "by-minute",
                    "By Minute",
                    null,
                    60_000L,
                    10_000_000L,
                    102L,
                ),
            ),
            episodeRows = emptyList(),
        )

        assertEquals(listOf("by-minute", "by-percent"), items.map { it.contentId })
    }

    @Test
    fun suppressionHidesOnlyProgressAtOrBeforeRemovalTimestamp() {
        val key = LibraryContinueWatchingSuppressionKey(
            LibraryContentKind.MOVIE,
            "movie-a",
        )
        val hidden = LibraryCatalogMapper.continueWatching(
            movieRows = listOf(
                LibraryMovieProgressRow("movie-a", "Movie A", null, 1L, 10L, 100L),
            ),
            episodeRows = emptyList(),
            suppression = mapOf(key to 100L),
        )
        assertEquals(emptyList<Any>(), hidden)

        val visibleAgain = LibraryCatalogMapper.continueWatching(
            movieRows = listOf(
                LibraryMovieProgressRow("movie-a", "Movie A", null, 2L, 10L, 101L),
            ),
            episodeRows = emptyList(),
            suppression = mapOf(key to 100L),
        )
        assertEquals(listOf("movie-a"), visibleAgain.map { it.contentId })
    }

    @Test
    fun continueWatchingUsesDeterministicTieBreakForSameTimestamp() {
        val items = LibraryCatalogMapper.continueWatching(
            movieRows = listOf(
                LibraryMovieProgressRow("movie-b", "Movie B", null, 1L, 10L, 100L),
                LibraryMovieProgressRow("movie-a", "Movie A", null, 1L, 10L, 100L),
            ),
            episodeRows = emptyList(),
        )

        assertEquals(listOf("movie-a", "movie-b"), items.map { it.contentId })
    }

    private fun episode(
        contentId: String,
        seriesId: String,
        updatedAt: Long,
    ) = LibraryEpisodeProgressRow(
        contentId = contentId,
        seriesId = seriesId,
        title = contentId,
        seriesTitle = seriesId,
        posterUrl = null,
        seasonNumber = 1,
        episodeNumber = 1,
        positionMs = 20_000L,
        durationMs = 80_000L,
        updatedAt = updatedAt,
    )
}
