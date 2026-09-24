package app.ownplay.mobile.feature.library.domain

object LibrarySearchPolicy {
    fun filterCatalog(
        catalog: LibraryCatalogSnapshot,
        query: String,
    ): LibraryCatalogSnapshot {
        val normalized = query.trim()
        if (normalized.isEmpty()) return catalog

        return catalog.copy(
            movies = catalog.movies.filter { it.title.contains(normalized, ignoreCase = true) },
            series = catalog.series.filter { it.title.contains(normalized, ignoreCase = true) },
            continueWatching = emptyList(),
            downloadedMedia = emptyList(),
        )
    }

    fun filterSeriesDetail(
        detail: LibrarySeriesDetail,
        query: String,
    ): LibrarySeriesDetail {
        val normalized = query.trim()
        if (normalized.isEmpty()) return detail

        return detail.copy(
            seasons = detail.seasons.mapNotNull { season ->
                val episodes = season.episodes.filter {
                    it.title.contains(normalized, ignoreCase = true)
                }
                season.copy(episodes = episodes).takeIf { episodes.isNotEmpty() }
            },
        )
    }
}
