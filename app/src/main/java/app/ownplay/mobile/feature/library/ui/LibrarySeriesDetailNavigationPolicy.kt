package app.ownplay.mobile.feature.library.ui

internal enum class LibrarySeriesDetailPage {
    DETAIL,
    SEASONS,
    EPISODES,
    EPISODE_DETAIL,
}

internal object LibrarySeriesDetailNavigationPolicy {
    fun parent(page: LibrarySeriesDetailPage): LibrarySeriesDetailPage? = when (page) {
        LibrarySeriesDetailPage.DETAIL -> null
        LibrarySeriesDetailPage.SEASONS -> LibrarySeriesDetailPage.DETAIL
        LibrarySeriesDetailPage.EPISODES -> LibrarySeriesDetailPage.SEASONS
        LibrarySeriesDetailPage.EPISODE_DETAIL -> LibrarySeriesDetailPage.EPISODES
    }
}
