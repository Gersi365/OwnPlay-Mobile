package app.ownplay.mobile.feature.library.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryEpisodeAutoplayPolicyTest {
    private val detail = LibrarySeriesDetail(
        series = LibrarySeriesSummary(
            seriesId = "series-a",
            categoryId = "category-a",
            title = "Series A",
            posterUrl = null,
            backdropUrl = null,
            description = null,
            rating = null,
            providerOrder = 0,
            favorite = false,
        ),
        seasons = listOf(
            LibrarySeason(
                seasonNumber = 2,
                episodes = listOf(
                    LibraryEpisodeSummary("s2e2", 2, 2, "S2 E2", null),
                    LibraryEpisodeSummary("s2e1", 2, 1, "S2 E1", null),
                ),
            ),
            LibrarySeason(
                seasonNumber = 1,
                episodes = listOf(
                    LibraryEpisodeSummary("s1e2", 1, 2, "S1 E2", null),
                    LibraryEpisodeSummary("s1e1", 1, 1, "S1 E1", null),
                ),
            ),
        ),
    )

    @Test
    fun nextEpisodeAdvancesWithinSeasonThenAcrossSeasonBoundary() {
        assertEquals("s1e2", LibraryEpisodeAutoplayPolicy.nextEpisode(detail, "s1e1")?.episodeId)
        assertEquals("s2e1", LibraryEpisodeAutoplayPolicy.nextEpisode(detail, "s1e2")?.episodeId)
    }

    @Test
    fun finalEpisodeHasNoAutoplayTarget() {
        assertNull(LibraryEpisodeAutoplayPolicy.nextEpisode(detail, "s2e2"))
    }

    @Test
    fun countdownUsesActualRemainingTimeInsideTenSecondWindow() {
        assertNull(LibraryEpisodeAutoplayPolicy.countdownSeconds(10_001L))
        assertEquals(10, LibraryEpisodeAutoplayPolicy.countdownSeconds(10_000L))
        assertEquals(4, LibraryEpisodeAutoplayPolicy.countdownSeconds(3_001L))
        assertEquals(1, LibraryEpisodeAutoplayPolicy.countdownSeconds(1L))
        assertEquals(0, LibraryEpisodeAutoplayPolicy.countdownSeconds(0L))
    }
}
