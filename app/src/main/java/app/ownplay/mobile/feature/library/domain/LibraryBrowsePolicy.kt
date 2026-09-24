package app.ownplay.mobile.feature.library.domain

import java.util.Locale

object LibraryBrowsePolicy {
    const val HOME_SHELF_LIMIT: Int = 10

    fun movies(
        items: List<LibraryMovieSummary>,
        query: String = "",
        sort: LibrarySortOption = LibrarySortOption.PROVIDER_ORDER,
        favoritesOnly: Boolean = false,
        categoryId: String? = null,
    ): List<LibraryMovieSummary> {
        val normalizedQuery = query.trim()
        return items.asSequence()
            .filter { !favoritesOnly || it.favorite }
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { normalizedQuery.isBlank() || it.title.contains(normalizedQuery, ignoreCase = true) }
            .sortedWith(movieComparator(sort))
            .toList()
    }

    fun series(
        items: List<LibrarySeriesSummary>,
        query: String = "",
        sort: LibrarySortOption = LibrarySortOption.PROVIDER_ORDER,
        favoritesOnly: Boolean = false,
        categoryId: String? = null,
    ): List<LibrarySeriesSummary> {
        val normalizedQuery = query.trim()
        return items.asSequence()
            .filter { !favoritesOnly || it.favorite }
            .filter { categoryId == null || it.categoryId == categoryId }
            .filter { normalizedQuery.isBlank() || it.title.contains(normalizedQuery, ignoreCase = true) }
            .sortedWith(seriesComparator(sort))
            .toList()
    }

    fun continueWatching(
        items: List<LibraryContinueWatchingItem>,
        query: String,
    ): List<LibraryContinueWatchingItem> {
        val normalizedQuery = query.trim()
        return items.asSequence()
            .filter {
                normalizedQuery.isBlank() ||
                    it.title.contains(normalizedQuery, ignoreCase = true) ||
                    it.seriesTitle?.contains(normalizedQuery, ignoreCase = true) == true
            }
            .sortedWith(
                compareByDescending<LibraryContinueWatchingItem> { it.updatedAt }
                    .thenBy { it.contentKind.name }
                    .thenBy { it.contentId },
            )
            .toList()
    }

    fun homeMovies(
        items: List<LibraryMovieSummary>,
        sort: LibrarySortOption,
    ): List<LibraryMovieSummary> =
        movies(items = items, sort = sort).take(HOME_SHELF_LIMIT)

    fun homeSeries(
        items: List<LibrarySeriesSummary>,
        sort: LibrarySortOption,
    ): List<LibrarySeriesSummary> =
        series(items = items, sort = sort).take(HOME_SHELF_LIMIT)

    private fun movieComparator(sort: LibrarySortOption): Comparator<LibraryMovieSummary> =
        when (sort) {
            LibrarySortOption.PROVIDER_ORDER ->
                compareBy<LibraryMovieSummary>(
                    LibraryMovieSummary::providerOrder,
                    LibraryMovieSummary::movieId,
                )
            LibrarySortOption.TITLE_ASC ->
                compareBy<LibraryMovieSummary> { it.title.lowercase(Locale.ROOT) }
                    .thenBy(LibraryMovieSummary::providerOrder)
                    .thenBy(LibraryMovieSummary::movieId)
            LibrarySortOption.RATING_DESC ->
                compareByDescending<LibraryMovieSummary> { ratingValue(it.rating) }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy(LibraryMovieSummary::movieId)
        }

    private fun seriesComparator(sort: LibrarySortOption): Comparator<LibrarySeriesSummary> =
        when (sort) {
            LibrarySortOption.PROVIDER_ORDER ->
                compareBy<LibrarySeriesSummary>(
                    LibrarySeriesSummary::providerOrder,
                    LibrarySeriesSummary::seriesId,
                )
            LibrarySortOption.TITLE_ASC ->
                compareBy<LibrarySeriesSummary> { it.title.lowercase(Locale.ROOT) }
                    .thenBy(LibrarySeriesSummary::providerOrder)
                    .thenBy(LibrarySeriesSummary::seriesId)
            LibrarySortOption.RATING_DESC ->
                compareByDescending<LibrarySeriesSummary> { ratingValue(it.rating) }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
                    .thenBy(LibrarySeriesSummary::seriesId)
        }

    private fun ratingValue(value: String?): Double =
        value?.trim()?.substringBefore('/')?.trim()?.toDoubleOrNull()
            ?: Double.NEGATIVE_INFINITY
}
