package app.ownplay.player.playback

import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.vod.VodMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandPresentationSessionTest {
    @Test
    fun moviePlaybackReturnsToItsDetailAndThenClears() {
        val session = OnDemandPresentationSession()
        val movie = movie("movie-1")

        session.showMovieCatalog("source-1")
        session.showMovieDetail("source-1", movie.movieId, returnToLibraryOnDetailBack = true)
        session.showMoviePlayback("source-1", movie, returnToLibraryOnDetailBack = true)

        assertTrue(session.current.isMoviePlayback)
        assertEquals(movie, session.current.moviePlayback)
        assertTrue(session.current.returnToLibraryOnDetailBack)

        session.returnFromMoviePlayback()

        assertFalse(session.current.isMoviePlayback)
        assertEquals(OnDemandContentKind.MOVIE, session.current.kind)
        assertEquals(movie.movieId, session.current.itemId)
        assertTrue(session.current.returnToLibraryOnDetailBack)

        session.clear()
        assertNull(session.current.kind)
    }

    @Test
    fun restoringSameMovieDetailDoesNotReplaceActivePlayback() {
        val session = OnDemandPresentationSession()
        val movie = movie("movie-restore")

        session.showMoviePlayback(
            sourceId = "source-1",
            movie = movie,
            returnToLibraryOnDetailBack = true,
        )
        val playingState = session.current

        session.showMovieDetail(
            sourceId = "source-1",
            movieId = movie.movieId,
            returnToLibraryOnDetailBack = false,
        )

        assertEquals(playingState, session.current)
        assertTrue(session.current.isMoviePlayback)
        assertEquals(movie, session.current.moviePlayback)
        assertTrue(session.current.returnToLibraryOnDetailBack)
    }

    @Test
    fun differentMovieDetailCanReplaceActiveMoviePlayback() {
        val session = OnDemandPresentationSession()
        val movie = movie("movie-playing")

        session.showMoviePlayback(
            sourceId = "source-1",
            movie = movie,
            returnToLibraryOnDetailBack = true,
        )
        session.showMovieDetail(
            sourceId = "source-1",
            movieId = "movie-next",
            returnToLibraryOnDetailBack = false,
        )

        assertFalse(session.current.isMoviePlayback)
        assertEquals(OnDemandContentKind.MOVIE, session.current.kind)
        assertEquals("movie-next", session.current.itemId)
        assertFalse(session.current.returnToLibraryOnDetailBack)
    }

    @Test
    fun seriesDetailPlaybackPreservesDrilldownOnReturn() {
        val session = OnDemandPresentationSession()
        val episode = episode("episode-4", "series-2", season = 3)

        session.showSeriesDetail(
            sourceId = "source-1",
            seriesId = episode.seriesId,
            seasonNumber = 3,
            episodeId = episode.episodeId,
            returnToLibraryOnDetailBack = true,
        )
        session.showSeriesPlayback(
            sourceId = "source-1",
            episode = episode,
            returnToLibraryOnDetailBack = true,
            returnToCatalog = false,
            selectedSeasonNumber = 3,
            selectedEpisodeId = episode.episodeId,
        )

        assertTrue(session.current.isSeriesPlayback)
        session.returnFromSeriesPlayback()

        assertFalse(session.current.isSeriesPlayback)
        assertEquals(OnDemandContentKind.SERIES, session.current.kind)
        assertEquals(episode.seriesId, session.current.itemId)
        assertEquals(3, session.current.seriesSeasonNumber)
        assertEquals(episode.episodeId, session.current.seriesEpisodeId)
        assertTrue(session.current.returnToLibraryOnDetailBack)
    }

    @Test
    fun seriesContinueWatchingPlaybackReturnsToCatalogSession() {
        val session = OnDemandPresentationSession()
        val episode = episode("episode-9", "series-5", season = 1)

        session.showSeriesCatalog("source-1")
        session.showSeriesPlayback(
            sourceId = "source-1",
            episode = episode,
            returnToLibraryOnDetailBack = false,
            returnToCatalog = true,
        )
        assertTrue(session.current.isSeriesPlayback)

        session.returnFromSeriesPlayback()

        assertEquals(OnDemandContentKind.SERIES, session.current.kind)
        assertEquals("source-1", session.current.sourceId)
        assertNull(session.current.itemId)
        assertFalse(session.current.isSeriesPlayback)
    }

    private fun movie(movieId: String) = VodMovie(
        movieId = movieId,
        providerStreamId = 10,
        categoryKey = "movies",
        name = "Movie",
        posterUrl = null,
        containerExtension = "mp4",
        rating = null,
        addedAtEpochSeconds = null,
        isFavorite = false,
        positionMs = 12_000L,
        durationMs = 120_000L,
        progressCompleted = false,
        progressUpdatedAtEpochMillis = null,
    )

    private fun episode(
        episodeId: String,
        seriesId: String,
        season: Int,
    ) = SeriesEpisode(
        episodeId = episodeId,
        seriesId = seriesId,
        seriesTitle = "Series",
        providerEpisodeId = 20,
        seasonNumber = season,
        episodeNumber = 4,
        title = "Episode",
        containerExtension = "mp4",
        durationSeconds = null,
        posterUrl = null,
        positionMs = 8_000L,
        durationMs = 90_000L,
        progressCompleted = false,
        progressUpdatedAtEpochMillis = null,
    )
}
