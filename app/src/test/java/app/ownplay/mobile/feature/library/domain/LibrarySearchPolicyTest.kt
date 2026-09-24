package app.ownplay.mobile.feature.library.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchPolicyTest {
    @Test
    fun catalogSearchMatchesMovieAndSeriesTitlesCaseInsensitivelyAndHidesNonSearchSections() {
        val catalog = LibraryCatalogSnapshot(
            movieCategories = emptyList(),
            seriesCategories = emptyList(),
            movies = listOf(movie("movie-a", "Alpha Movie"), movie("movie-b", "Beta")),
            series = listOf(series("series-a", "ALPHA Series"), series("series-b", "Gamma")),
            continueWatching = listOf(
                LibraryContinueWatchingItem(
                    contentKind = LibraryContentKind.MOVIE,
                    contentId = "movie-a",
                    title = "Alpha Movie",
                    posterUrl = null,
                    positionMs = 1_000L,
                    durationMs = 10_000L,
                    updatedAt = 5L,
                ),
            ),
            downloadedMedia = listOf(
                LibraryDownloadedMediaItem(
                    downloadId = "download-a",
                    contentKind = LibraryContentKind.MOVIE,
                    contentId = "movie-a",
                    title = "Alpha Movie",
                    bytesDownloaded = 100L,
                    totalBytes = 100L,
                    updatedAt = 6L,
                ),
            ),
        )

        val filtered = LibrarySearchPolicy.filterCatalog(catalog, " alpha ")

        assertEquals(listOf("movie-a"), filtered.movies.map { it.movieId })
        assertEquals(listOf("series-a"), filtered.series.map { it.seriesId })
        assertTrue(filtered.continueWatching.isEmpty())
        assertTrue(filtered.downloadedMedia.isEmpty())
    }

    @Test
    fun blankCatalogSearchPreservesOriginalSnapshot() {
        val catalog = LibraryCatalogSnapshot(
            movieCategories = emptyList(),
            seriesCategories = emptyList(),
            movies = listOf(movie("movie-a", "Alpha")),
            series = emptyList(),
            downloadedMedia = listOf(
                LibraryDownloadedMediaItem(
                    downloadId = "download-a",
                    contentKind = LibraryContentKind.MOVIE,
                    contentId = "movie-a",
                    title = "Alpha",
                    bytesDownloaded = 100L,
                    totalBytes = 100L,
                    updatedAt = 1L,
                ),
            ),
        )

        assertEquals(catalog, LibrarySearchPolicy.filterCatalog(catalog, "   "))
    }

    @Test
    fun episodeSearchKeepsOnlyMatchingEpisodesAndDropsEmptySeasons() {
        val detail = LibrarySeriesDetail(
            series = series("series-a", "Series"),
            seasons = listOf(
                LibrarySeason(
                    seasonNumber = 1,
                    episodes = listOf(
                        LibraryEpisodeSummary("e-1", 1, 1, "Pilot", null),
                        LibraryEpisodeSummary("e-2", 1, 2, "Finale", null),
                    ),
                ),
                LibrarySeason(
                    seasonNumber = 2,
                    episodes = listOf(
                        LibraryEpisodeSummary("e-3", 2, 1, "Return", null),
                    ),
                ),
            ),
        )

        val filtered = LibrarySearchPolicy.filterSeriesDetail(detail, "final")

        assertEquals(listOf(1), filtered.seasons.map { it.seasonNumber })
        assertEquals(listOf("e-2"), filtered.seasons.single().episodes.map { it.episodeId })
    }

    private fun movie(id: String, title: String) = LibraryMovieSummary(
        movieId = id,
        categoryId = null,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        rating = null,
        providerOrder = 0,
        favorite = false,
    )

    private fun series(id: String, title: String) = LibrarySeriesSummary(
        seriesId = id,
        categoryId = null,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        description = null,
        rating = null,
        providerOrder = 0,
        favorite = false,
    )
}
