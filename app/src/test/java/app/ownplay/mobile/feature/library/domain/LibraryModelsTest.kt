package app.ownplay.mobile.feature.library.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryModelsTest {
    @Test
    fun startPolicyUsesSelectedEpisodeWhenPresent() {
        val detail = detail()

        val selected = LibraryDetailStartPolicy.selectedOrFirstAvailable(
            detail = detail,
            selectedEpisodeId = "episode-2",
        )

        assertEquals("episode-2", selected?.episodeId)
    }

    @Test
    fun startPolicyFallsBackDeterministicallyToFirstSeasonAndEpisode() {
        val detail = detail()

        val selected = LibraryDetailStartPolicy.selectedOrFirstAvailable(
            detail = detail,
            selectedEpisodeId = "missing",
        )

        assertEquals("episode-1", selected?.episodeId)
    }

    @Test
    fun startPolicyReturnsNullWhenNoEpisodesAreCached() {
        val detail = detail().copy(seasons = emptyList())

        assertNull(LibraryDetailStartPolicy.firstAvailableEpisode(detail))
    }

    private fun detail(): LibrarySeriesDetail = LibrarySeriesDetail(
        series = LibrarySeriesSummary(
            seriesId = "series-1",
            categoryId = "category-1",
            title = "Series",
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
                    LibraryEpisodeSummary("episode-2", 2, 1, "Episode 2", null),
                ),
            ),
            LibrarySeason(
                seasonNumber = 1,
                episodes = listOf(
                    LibraryEpisodeSummary("episode-1", 1, 1, "Episode 1", null),
                ),
            ),
        ),
    )
}
