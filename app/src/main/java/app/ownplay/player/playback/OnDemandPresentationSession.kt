package app.ownplay.player.playback

import app.ownplay.player.series.SeriesEpisode
import app.ownplay.player.vod.VodMovie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnDemandContentKind {
    MOVIE,
    SERIES,
}

data class OnDemandPresentationState(
    val kind: OnDemandContentKind? = null,
    val sourceId: String? = null,
    val itemId: String? = null,
    val returnToLibraryOnDetailBack: Boolean = false,
    val seriesSeasonNumber: Int? = null,
    val seriesEpisodeId: String? = null,
    val moviePlayback: VodMovie? = null,
    val seriesPlayback: SeriesEpisode? = null,
    val seriesPlaybackReturnsToCatalog: Boolean = false,
) {
    init {
        if (kind == null) {
            require(sourceId == null)
            require(itemId == null)
            require(!returnToLibraryOnDetailBack)
            require(seriesSeasonNumber == null)
            require(seriesEpisodeId == null)
            require(moviePlayback == null)
            require(seriesPlayback == null)
            require(!seriesPlaybackReturnsToCatalog)
        } else {
            require(!sourceId.isNullOrBlank())
        }

        when (kind) {
            OnDemandContentKind.MOVIE -> {
                require(seriesSeasonNumber == null)
                require(seriesEpisodeId == null)
                require(seriesPlayback == null)
                require(!seriesPlaybackReturnsToCatalog)
                moviePlayback?.let { movie -> require(itemId == movie.movieId) }
            }
            OnDemandContentKind.SERIES -> {
                require(moviePlayback == null)
                require(seriesEpisodeId == null || seriesSeasonNumber != null)
                seriesPlayback?.let { episode -> require(itemId == episode.seriesId) }
                require(seriesPlayback != null || !seriesPlaybackReturnsToCatalog)
            }
            null -> Unit
        }
    }

    val isMoviePlayback: Boolean
        get() = kind == OnDemandContentKind.MOVIE && moviePlayback != null

    val isSeriesPlayback: Boolean
        get() = kind == OnDemandContentKind.SERIES && seriesPlayback != null
}

/**
 * Transient process-scoped presentation state for online Movies and Series.
 *
 * This state is intentionally not persisted. Activity recreation can rebuild catalog/detail/playback
 * presentation around the already process-scoped playback runtime, while process death still starts
 * with an empty session and cannot trigger cold-start autoplay.
 */
class OnDemandPresentationSession {
    private val _state = MutableStateFlow(OnDemandPresentationState())
    val state: StateFlow<OnDemandPresentationState> = _state.asStateFlow()

    val current: OnDemandPresentationState
        get() = _state.value

    fun showMovieCatalog(sourceId: String) {
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.MOVIE,
            sourceId = sourceId,
        )
    }

    fun showMovieDetail(
        sourceId: String,
        movieId: String,
        returnToLibraryOnDetailBack: Boolean,
    ) {
        val current = _state.value
        if (
            current.isMoviePlayback &&
            current.sourceId == sourceId &&
            current.itemId == movieId
        ) {
            return
        }
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.MOVIE,
            sourceId = sourceId,
            itemId = movieId,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
        )
    }

    fun showMoviePlayback(
        sourceId: String,
        movie: VodMovie,
        returnToLibraryOnDetailBack: Boolean,
    ) {
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.MOVIE,
            sourceId = sourceId,
            itemId = movie.movieId,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            moviePlayback = movie,
        )
    }

    fun returnFromMoviePlayback() {
        val current = _state.value
        if (!current.isMoviePlayback) return
        _state.value = current.copy(moviePlayback = null)
    }

    fun showSeriesCatalog(sourceId: String) {
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.SERIES,
            sourceId = sourceId,
        )
    }

    fun showSeriesDetail(
        sourceId: String,
        seriesId: String,
        seasonNumber: Int? = null,
        episodeId: String? = null,
        returnToLibraryOnDetailBack: Boolean,
    ) {
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.SERIES,
            sourceId = sourceId,
            itemId = seriesId,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            seriesSeasonNumber = seasonNumber,
            seriesEpisodeId = episodeId,
        )
    }

    fun updateSeriesSelection(
        seasonNumber: Int?,
        episodeId: String?,
    ) {
        val current = _state.value
        if (current.kind != OnDemandContentKind.SERIES || current.itemId == null) return
        _state.value = current.copy(
            seriesSeasonNumber = seasonNumber,
            seriesEpisodeId = episodeId,
        )
    }

    fun showSeriesPlayback(
        sourceId: String,
        episode: SeriesEpisode,
        returnToLibraryOnDetailBack: Boolean,
        returnToCatalog: Boolean,
        selectedSeasonNumber: Int? = null,
        selectedEpisodeId: String? = null,
    ) {
        _state.value = OnDemandPresentationState(
            kind = OnDemandContentKind.SERIES,
            sourceId = sourceId,
            itemId = episode.seriesId,
            returnToLibraryOnDetailBack = returnToLibraryOnDetailBack,
            seriesSeasonNumber = selectedSeasonNumber,
            seriesEpisodeId = selectedEpisodeId,
            seriesPlayback = episode,
            seriesPlaybackReturnsToCatalog = returnToCatalog,
        )
    }

    fun returnFromSeriesPlayback() {
        val current = _state.value
        if (!current.isSeriesPlayback) return
        if (current.seriesPlaybackReturnsToCatalog) {
            showSeriesCatalog(requireNotNull(current.sourceId))
        } else {
            _state.value = current.copy(
                seriesPlayback = null,
                seriesPlaybackReturnsToCatalog = false,
            )
        }
    }

    fun clear() {
        _state.value = OnDemandPresentationState()
    }
}
