package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.LibraryDownloadImportEpisodeRow
import app.ownplay.mobile.data.db.MovieEntity
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalDownloadImportMatcherTest {
    @Test
    fun movieWithYearAndReleaseTagsMatchesUniqueProviderMovie() {
        val match = ExternalDownloadImportMatcher.match(
            file = file("Avatar.2009.1080p.x265.mkv"),
            movies = listOf(movie("movie-avatar", "Avatar")),
            episodes = emptyList(),
        )
        requireNotNull(match)
        assertEquals(DownloadMediaKind.MOVIE, match.mediaKind)
        assertEquals("movie-avatar", match.contentId)
        assertEquals("Avatar", match.title)
    }

    @Test
    fun ambiguousMovieTitleIsNotImported() {
        val match = ExternalDownloadImportMatcher.match(
            file = file("Dune.1080p.mkv"),
            movies = listOf(movie("dune-a", "Dune"), movie("dune-b", "Dune")),
            episodes = emptyList(),
        )
        assertNull(match)
        val candidates = ExternalDownloadImportMatcher.candidates(
            file = file("Dune.1080p.mkv"),
            movies = listOf(movie("dune-a", "Dune"), movie("dune-b", "Dune")),
            episodes = emptyList(),
        )
        assertEquals(listOf("dune-a", "dune-b"), candidates.map { it.contentId })
    }

    @Test
    fun episodeUsesSeriesFolderAndSeasonEpisodeToken() {
        val match = ExternalDownloadImportMatcher.match(
            file = file("S02E05 - Madrigal.mkv", listOf("Series", "Breaking Bad", "Season 02")),
            movies = emptyList(),
            episodes = listOf(episode("episode-205", "series-bb", "Breaking Bad", 2, 5, "Madrigal")),
        )
        requireNotNull(match)
        assertEquals(DownloadMediaKind.EPISODE, match.mediaKind)
        assertEquals("episode-205", match.contentId)
        assertEquals("Madrigal", match.title)
    }

    @Test
    fun episodeFilenameCanProvideSeriesNameWhenFileIsAtFolderRoot() {
        val match = ExternalDownloadImportMatcher.match(
            file = file("Breaking.Bad.S03E07.720p.mkv"),
            movies = emptyList(),
            episodes = listOf(episode("episode-307", "series-bb", "Breaking Bad", 3, 7, "One Minute")),
        )
        requireNotNull(match)
        assertEquals("episode-307", match.contentId)
    }

    @Test
    fun unknownTrailingMovieWordsDoNotProduceGuess() {
        assertNull(
            ExternalDownloadImportMatcher.match(
                file = file("The.Matrix.Reloaded.mkv"),
                movies = listOf(movie("matrix", "The Matrix")),
                episodes = emptyList(),
            ),
        )
    }

    private fun file(name: String, path: List<String> = emptyList()) = ExternalDownloadImportFile(
        uri = "content://example/$name",
        displayName = name,
        sizeBytes = 1024L,
        relativePathSegments = path,
    )

    private fun movie(id: String, name: String) = MovieEntity(
        movieId = id,
        sourceId = "source-a",
        providerStreamId = id,
        categoryKey = null,
        name = name,
        posterUrl = "https://example/$id.jpg",
        backdropUrl = null,
        extension = "mkv",
        rating = null,
        providerOrder = 0,
        available = true,
        lastSeenGeneration = 1L,
    )

    private fun episode(
        episodeId: String,
        seriesId: String,
        seriesTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        title: String,
    ) = LibraryDownloadImportEpisodeRow(
        episodeId = episodeId,
        seriesId = seriesId,
        seriesTitle = seriesTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        title = title,
    )
}
