package app.ownplay.mobile.feature.library.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBrowsePolicyTest {
    @Test
    fun providerOrderUsesStableIdentityTieBreakers() {
        val movies = listOf(
            movie("movie-b", "Beta", order = 1),
            movie("movie-a", "Alpha", order = 1),
            movie("movie-c", "Gamma", order = 0),
        )

        val ordered = LibraryBrowsePolicy.movies(
            items = movies,
            sort = LibrarySortOption.PROVIDER_ORDER,
        )

        assertEquals(listOf("movie-c", "movie-a", "movie-b"), ordered.map { it.movieId })
    }

    @Test
    fun titleAndRatingSortsAreDeterministic() {
        val movies = listOf(
            movie("movie-c", "gamma", order = 0, rating = null),
            movie("movie-b", "Beta", order = 1, rating = "8.7/10"),
            movie("movie-a", "alpha", order = 2, rating = "8.7"),
        )

        assertEquals(
            listOf("movie-a", "movie-b", "movie-c"),
            LibraryBrowsePolicy.movies(
                items = movies,
                sort = LibrarySortOption.TITLE_ASC,
            ).map { it.movieId },
        )
        assertEquals(
            listOf("movie-a", "movie-b", "movie-c"),
            LibraryBrowsePolicy.movies(
                items = movies,
                sort = LibrarySortOption.RATING_DESC,
            ).map { it.movieId },
        )
    }

    @Test
    fun categoryFavoriteAndSearchFiltersCompose() {
        val series = listOf(
            series("series-a", "Alpha", category = "cat-a", favorite = true),
            series("series-b", "Alpha Two", category = "cat-b", favorite = true),
            series("series-c", "Gamma", category = "cat-a", favorite = false),
        )

        val filtered = LibraryBrowsePolicy.series(
            items = series,
            query = "alpha",
            favoritesOnly = true,
            categoryId = "cat-a",
        )

        assertEquals(listOf("series-a"), filtered.map { it.seriesId })
    }

    @Test
    fun homeShelvesAreBoundedToTenItems() {
        val movies = (0 until 12).map { index ->
            movie(
                id = "movie-" + index.toString().padStart(2, '0'),
                title = "Movie " + index,
                order = index,
            )
        }

        assertEquals(
            10,
            LibraryBrowsePolicy.homeMovies(
                items = movies,
                sort = LibrarySortOption.PROVIDER_ORDER,
            ).size,
        )
    }

    @Test
    fun continueWatchingSearchMatchesEpisodeOrSeriesTitleAndKeepsRecentFirst() {
        val items = listOf(
            continueItem("episode-a", "Pilot", "Alpha Show", 10L),
            continueItem("episode-b", "Finale", "Beta Show", 20L),
        )

        assertEquals(
            listOf("episode-a"),
            LibraryBrowsePolicy.continueWatching(items, "alpha").map { it.contentId },
        )
        assertEquals(
            listOf("episode-b", "episode-a"),
            LibraryBrowsePolicy.continueWatching(items, "").map { it.contentId },
        )
    }

    private fun movie(
        id: String,
        title: String,
        order: Int,
        rating: String? = null,
        favorite: Boolean = false,
    ) = LibraryMovieSummary(
        movieId = id,
        categoryId = "cat-a",
        title = title,
        posterUrl = null,
        backdropUrl = null,
        rating = rating,
        providerOrder = order,
        favorite = favorite,
    )

    private fun series(
        id: String,
        title: String,
        category: String,
        favorite: Boolean,
    ) = LibrarySeriesSummary(
        seriesId = id,
        categoryId = category,
        title = title,
        posterUrl = null,
        backdropUrl = null,
        description = null,
        rating = null,
        providerOrder = 0,
        favorite = favorite,
    )

    private fun continueItem(
        id: String,
        title: String,
        seriesTitle: String,
        updatedAt: Long,
    ) = LibraryContinueWatchingItem(
        contentKind = LibraryContentKind.EPISODE,
        contentId = id,
        title = title,
        seriesId = "series-" + id,
        seriesTitle = seriesTitle,
        seasonNumber = 1,
        episodeNumber = 1,
        posterUrl = null,
        positionMs = 60_000L,
        durationMs = 600_000L,
        updatedAt = updatedAt,
    )
}
