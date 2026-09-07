package app.ownplay.player.ui.library

import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.vod.VodMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryContinueWatchingTest {
    @Test
    fun `progress is unavailable without a positive duration`() {
        assertNull(progressFraction(positionMs = 10_000L, durationMs = null))
        assertNull(progressFraction(positionMs = 10_000L, durationMs = 0L))
        assertNull(progressFraction(positionMs = 10_000L, durationMs = -1L))
    }

    @Test
    fun `progress uses the watched fraction`() {
        assertEquals(0.25f, progressFraction(positionMs = 15_000L, durationMs = 60_000L)!!, 0.0001f)
    }

    @Test
    fun `progress clamps to visible bounds`() {
        assertEquals(0f, progressFraction(positionMs = -5_000L, durationMs = 60_000L)!!, 0.0001f)
        assertEquals(1f, progressFraction(positionMs = 75_000L, durationMs = 60_000L)!!, 0.0001f)
    }

    @Test
    fun `resume label includes rounded watched percent`() {
        assertEquals(
            "Resume · 25%",
            continueWatchingResumeLabel(positionMs = 15_000L, durationMs = 60_000L),
        )
        assertEquals(
            "Resume · 33%",
            continueWatchingResumeLabel(positionMs = 20_000L, durationMs = 60_000L),
        )
    }

    @Test
    fun `resume label falls back when duration is unavailable`() {
        assertEquals("Resume", continueWatchingResumeLabel(positionMs = 15_000L, durationMs = null))
        assertEquals("Resume", continueWatchingResumeLabel(positionMs = 15_000L, durationMs = 0L))
    }

    @Test
    fun `unified Continue Watching mixes movies and episodes by latest progress`() {
        val items = unifiedContinueWatching(
            movies = listOf(movie(id = "movie-1", updatedAt = 200L)),
            episodes = listOf(
                episode(id = "episode-1", updatedAt = 300L),
                episode(id = "episode-2", updatedAt = 100L),
            ),
        )

        assertEquals(
            listOf("episode:episode-1", "movie:movie-1", "episode:episode-2"),
            items.map(LibraryContinueWatchingItem::stableKey),
        )
    }

    @Test
    fun `unified Continue Watching limit is deterministic`() {
        val items = unifiedContinueWatching(
            movies = listOf(
                movie(id = "b", updatedAt = 100L),
                movie(id = "a", updatedAt = 100L),
            ),
            episodes = emptyList(),
            limit = 1,
        )

        assertEquals(listOf("movie:a"), items.map(LibraryContinueWatchingItem::stableKey))
        assertEquals(emptyList<LibraryContinueWatchingItem>(), unifiedContinueWatching(emptyList(), emptyList(), 0))
    }

    private fun movie(id: String, updatedAt: Long): VodMovie = VodMovie(
        movieId = id,
        providerStreamId = 1,
        categoryKey = null,
        name = id,
        posterUrl = null,
        containerExtension = null,
        rating = null,
        addedAtEpochSeconds = null,
        isFavorite = false,
        positionMs = 10_000L,
        durationMs = 60_000L,
        progressCompleted = false,
        progressUpdatedAtEpochMillis = updatedAt,
    )

    private fun episode(id: String, updatedAt: Long): SeriesEpisode = SeriesEpisode(
        episodeId = id,
        seriesId = "series-$id",
        seriesTitle = "Series $id",
        providerEpisodeId = 1,
        seasonNumber = 1,
        episodeNumber = 1,
        title = "Episode $id",
        containerExtension = null,
        durationSeconds = null,
        posterUrl = null,
        positionMs = 10_000L,
        durationMs = 60_000L,
        progressCompleted = false,
        progressUpdatedAtEpochMillis = updatedAt,
    )
}
