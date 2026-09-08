package app.ownplay.player.ui

import app.ownplay.player.series.SeriesSummary
import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceText
import app.ownplay.player.vod.VodMovie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileHomePresentationTest {
    @Test
    fun `recent movies are deterministic and newest first`() {
        val movies = listOf(
            movie(id = "older", name = "Zulu", added = 100L),
            movie(id = "b", name = "Beta", added = 300L),
            movie(id = "a", name = "Alpha", added = 300L),
            movie(id = "unknown", name = "Unknown", added = null),
        )

        assertEquals(
            listOf("a", "b", "older"),
            homeRecentMovies(movies, limit = 3).map(VodMovie::movieId),
        )
        assertTrue(homeRecentMovies(movies, limit = 0).isEmpty())
    }

    @Test
    fun `recent series are deterministic and latest modified first`() {
        val series = listOf(
            series(id = "old", name = "Zulu", modified = 50L),
            series(id = "b", name = "Beta", modified = 400L),
            series(id = "a", name = "Alpha", modified = 400L),
            series(id = "unknown", name = "Unknown", modified = null),
        )

        assertEquals(
            listOf("a", "b", "old"),
            homeRecentSeries(series, limit = 3).map(SeriesSummary::seriesId),
        )
        assertTrue(homeRecentSeries(series, limit = -1).isEmpty())
    }

    @Test
    fun `Home is cache first media content with stable geometry`() {
        val home = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextHomeRoute.kt"),
        )
        val shell = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextOwnPlayApp.kt"),
        )
        val continueWatching = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/library/LibraryContinueWatching.kt"),
        )

        assertTrue(home.contains("combine("))
        assertTrue(home.contains("unifiedContinueWatching("))
        assertTrue(home.contains("Recently Added Movies"))
        assertTrue(home.contains("Recently Updated Series"))
        assertTrue(home.contains("items(items = items, key = stableKey)"))
        assertTrue(home.contains("aspectRatio(OwnPlayMediaLayout.PosterAspectRatio)"))
        assertFalse(home.contains(".refresh("))

        assertTrue(shell.contains("movieDetailReturnDestination = MobileShellDestination.HOME"))
        assertTrue(shell.contains("seriesDetailReturnDestination = MobileShellDestination.HOME"))
        assertTrue(shell.contains("onReturnToLibrary = { openSection(movieDetailReturnDestination) }"))
        assertTrue(shell.contains("onReturnToLibrary = { openSection(seriesDetailReturnDestination) }"))

        assertTrue(continueWatching.contains("val cardWidth = 220.dp"))
        assertTrue(continueWatching.contains("val posterWidth = 58.dp"))
        assertFalse(continueWatching.contains("val cardWidth = 138.dp"))
    }

    private fun movie(
        id: String,
        name: String,
        added: Long?,
    ) = VodMovie(
        movieId = id,
        providerStreamId = id.hashCode(),
        categoryKey = null,
        name = name,
        posterUrl = null,
        containerExtension = null,
        rating = null,
        addedAtEpochSeconds = added,
        isFavorite = false,
        positionMs = null,
        durationMs = null,
        progressCompleted = false,
        progressUpdatedAtEpochMillis = null,
    )

    private fun series(
        id: String,
        name: String,
        modified: Long?,
    ) = SeriesSummary(
        seriesId = id,
        providerSeriesId = id.hashCode(),
        categoryKey = null,
        name = name,
        posterUrl = null,
        description = null,
        rating = null,
        lastModifiedEpochSeconds = modified,
        isFavorite = false,
    )
}
