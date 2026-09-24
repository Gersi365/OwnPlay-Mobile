package app.ownplay.mobile.feature.library.domain

import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.Flow

enum class LibraryContentKind {
    MOVIE,
    SERIES,
    EPISODE,
}

enum class LibrarySortOption {
    PROVIDER_ORDER,
    TITLE_ASC,
    RATING_DESC,
}

enum class LibraryDetailRefreshResult {
    REFRESHED,
    UNAVAILABLE,
    UNSUPPORTED_SOURCE,
    FAILED,
}

data class LibraryCategory(
    val categoryId: String,
    val displayName: String,
    val providerOrder: Int,
)

data class LibraryMovieSummary(
    val movieId: String,
    val categoryId: String?,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: String?,
    val providerOrder: Int,
    val favorite: Boolean,
)

data class LibraryMovieDetailMetadata(
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val releaseDate: String?,
    val year: String?,
    val runtimeMs: Long?,
    val rating: String?,
    val cast: List<String> = emptyList(),
) {
    init {
        require(runtimeMs == null || runtimeMs > 0L) { "Movie runtime must be positive when present" }
    }
}

sealed interface LibraryMovieDetailLoadResult {
    data class Loaded(val metadata: LibraryMovieDetailMetadata) : LibraryMovieDetailLoadResult
    data object Unavailable : LibraryMovieDetailLoadResult
    data object UnsupportedSource : LibraryMovieDetailLoadResult
    data object Failed : LibraryMovieDetailLoadResult
}

data class LibrarySeriesDetailMetadata(
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val releaseDate: String?,
    val year: String?,
    val genre: String?,
    val rating: String?,
    val cast: List<String> = emptyList(),
)

sealed interface LibrarySeriesMetadataLoadResult {
    data class Loaded(val metadata: LibrarySeriesDetailMetadata) : LibrarySeriesMetadataLoadResult
    data object Unavailable : LibrarySeriesMetadataLoadResult
    data object UnsupportedSource : LibrarySeriesMetadataLoadResult
    data object Failed : LibrarySeriesMetadataLoadResult
}

data class LibrarySeriesSummary(
    val seriesId: String,
    val categoryId: String?,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val rating: String?,
    val providerOrder: Int,
    val favorite: Boolean,
)

data class LibraryEpisodeSummary(
    val episodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val durationMs: Long?,
)

data class LibrarySeason(
    val seasonNumber: Int,
    val episodes: List<LibraryEpisodeSummary>,
)

data class LibrarySeriesDetail(
    val series: LibrarySeriesSummary,
    val seasons: List<LibrarySeason>,
)

data class LibraryContinueWatchingItem(
    val contentKind: LibraryContentKind,
    val contentId: String,
    val title: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    init {
        require(contentKind == LibraryContentKind.MOVIE || contentKind == LibraryContentKind.EPISODE) {
            "Continue Watching supports Movies and Episodes"
        }
        require(contentId.isNotBlank()) { "Continue Watching content id must not be blank" }
        require(positionMs > 0L) { "Continue Watching position must be positive" }
        require(durationMs > 0L) { "Continue Watching duration must be positive" }
    }
}

data class LibraryDownloadedMediaItem(
    val downloadId: String,
    val contentKind: LibraryContentKind,
    val contentId: String,
    val title: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val updatedAt: Long,
) {
    init {
        require(downloadId.isNotBlank()) { "Downloaded media id must not be blank" }
        require(contentKind == LibraryContentKind.MOVIE || contentKind == LibraryContentKind.EPISODE) {
            "Downloaded media supports Movies and Episodes"
        }
        require(contentId.isNotBlank()) { "Downloaded media content id must not be blank" }
        require(title.isNotBlank()) { "Downloaded media title must not be blank" }
        require(bytesDownloaded > 0L) { "Downloaded media must contain downloaded bytes" }
    }
}

data class LibraryCatalogSnapshot(
    val movieCategories: List<LibraryCategory>,
    val seriesCategories: List<LibraryCategory>,
    val movies: List<LibraryMovieSummary>,
    val series: List<LibrarySeriesSummary>,
    val continueWatching: List<LibraryContinueWatchingItem> = emptyList(),
    val downloadedMedia: List<LibraryDownloadedMediaItem> = emptyList(),
)

interface LibraryRepository {
    fun observeCatalog(sourceId: SourceId): Flow<LibraryCatalogSnapshot>

    fun observeMovie(
        sourceId: SourceId,
        movieId: String,
    ): Flow<LibraryMovieSummary?>

    suspend fun loadMovieDetail(
        sourceId: SourceId,
        movieId: String,
    ): LibraryMovieDetailLoadResult

    fun observeSeriesDetail(
        sourceId: SourceId,
        seriesId: String,
    ): Flow<LibrarySeriesDetail?>

    suspend fun loadSeriesMetadata(
        sourceId: SourceId,
        seriesId: String,
    ): LibrarySeriesMetadataLoadResult = LibrarySeriesMetadataLoadResult.UnsupportedSource

    suspend fun refreshSeriesDetail(
        sourceId: SourceId,
        seriesId: String,
    ): LibraryDetailRefreshResult

    suspend fun setFavorite(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
        favorite: Boolean,
    ): Boolean

    suspend fun removeFromContinueWatching(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
    ): Boolean
}

object LibraryContinueWatchingPolicy {
    private const val MIN_POSITION_MS = 60_000L
    private const val MIN_FRACTION = 0.02

    fun isEligible(
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (positionMs <= 0L || durationMs <= 0L) return false
        val boundedPosition = positionMs.coerceAtMost(durationMs)
        return boundedPosition >= MIN_POSITION_MS ||
            boundedPosition.toDouble() / durationMs.toDouble() >= MIN_FRACTION
    }
}

object LibraryEpisodeAutoplayPolicy {
    const val COUNTDOWN_WINDOW_MS: Long = 10_000L

    fun nextEpisode(
        detail: LibrarySeriesDetail,
        currentEpisodeId: String,
    ): LibraryEpisodeSummary? {
        if (currentEpisodeId.isBlank()) return null
        val ordered = detail.seasons
            .asSequence()
            .sortedBy(LibrarySeason::seasonNumber)
            .flatMap { season ->
                season.episodes
                    .asSequence()
                    .sortedWith(
                        compareBy<LibraryEpisodeSummary>(
                            LibraryEpisodeSummary::episodeNumber,
                            LibraryEpisodeSummary::episodeId,
                        ),
                    )
            }
            .toList()
        val index = ordered.indexOfFirst { it.episodeId == currentEpisodeId }
        return if (index >= 0) ordered.getOrNull(index + 1) else null
    }

    fun countdownSeconds(remainingMs: Long): Int? {
        if (remainingMs < 0L || remainingMs > COUNTDOWN_WINDOW_MS) return null
        return ((remainingMs + 999L) / 1_000L).toInt()
    }
}

object LibraryDetailStartPolicy {
    fun firstAvailableEpisode(detail: LibrarySeriesDetail): LibraryEpisodeSummary? =
        detail.seasons
            .asSequence()
            .sortedBy(LibrarySeason::seasonNumber)
            .flatMap { season ->
                season.episodes
                    .asSequence()
                    .sortedWith(
                        compareBy<LibraryEpisodeSummary>(
                            LibraryEpisodeSummary::episodeNumber,
                            LibraryEpisodeSummary::episodeId,
                        ),
                    )
            }
            .firstOrNull()

    fun selectedOrFirstAvailable(
        detail: LibrarySeriesDetail,
        selectedEpisodeId: String?,
    ): LibraryEpisodeSummary? {
        val selected = selectedEpisodeId
            ?.takeIf(String::isNotBlank)
            ?.let { episodeId ->
                detail.seasons
                    .asSequence()
                    .flatMap { it.episodes.asSequence() }
                    .firstOrNull { it.episodeId == episodeId }
            }
        return selected ?: firstAvailableEpisode(detail)
    }
}
