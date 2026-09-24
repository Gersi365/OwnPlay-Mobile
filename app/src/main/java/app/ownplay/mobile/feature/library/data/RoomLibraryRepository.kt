package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.data.db.EpisodeEntity
import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.LibraryEpisodeProgressRow
import app.ownplay.mobile.data.db.LibraryMovieProgressRow
import app.ownplay.mobile.data.db.MediaFavoriteEntity
import app.ownplay.mobile.data.db.MovieEntity
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.SeriesEntity
import app.ownplay.mobile.feature.library.domain.LibraryCatalogSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryCategory
import app.ownplay.mobile.feature.library.domain.LibraryContentKind
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingPolicy
import app.ownplay.mobile.feature.library.domain.LibraryContinueWatchingItem
import app.ownplay.mobile.feature.library.domain.LibraryDetailRefreshResult
import app.ownplay.mobile.feature.library.domain.LibraryDownloadedMediaItem
import app.ownplay.mobile.feature.library.domain.LibraryEpisodeSummary
import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailLoadResult
import app.ownplay.mobile.feature.library.domain.LibraryMovieSummary
import app.ownplay.mobile.feature.library.domain.LibraryRepository
import app.ownplay.mobile.feature.library.domain.LibrarySeason
import app.ownplay.mobile.feature.library.domain.LibrarySeriesDetail
import app.ownplay.mobile.feature.library.domain.LibrarySeriesMetadataLoadResult
import app.ownplay.mobile.feature.library.domain.LibrarySeriesSummary
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

class RoomLibraryRepository internal constructor(
    private val dao: LibraryDao,
    private val detailRefresher: LibrarySeriesDetailRefresher,
    private val movieDetailLoader: LibraryMovieDetailLoader,
    private val continueWatchingSuppressionStore: LibraryContinueWatchingSuppressionStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : LibraryRepository {
    override fun observeCatalog(sourceId: SourceId): Flow<LibraryCatalogSnapshot> {
        val categories = combine(
            dao.observeMovieCategories(sourceId.value),
            dao.observeSeriesCategories(sourceId.value),
        ) { movieCategories, seriesCategories ->
            CategoryRows(movieCategories, seriesCategories)
        }
        val media = combine(
            dao.observeMovies(sourceId.value),
            dao.observeSeries(sourceId.value),
        ) { movies, series ->
            MediaRows(movies, series)
        }
        val progress = combine(
            combine(
                dao.observeMovieContinueWatching(sourceId.value),
                dao.observeEpisodeContinueWatching(sourceId.value),
            ) { movies, episodes ->
                ProgressRows(movies, episodes)
            },
            continueWatchingSuppressionStore.observe(sourceId),
        ) { rows, suppression ->
            rows.copy(suppression = suppression)
        }

        return combine(
            categories,
            media,
            dao.observeFavorites(sourceId.value),
            progress,
            dao.observeCompletedDownloads(sourceId.value),
        ) { categoryRows, mediaRows, favorites, progressRows, downloads ->
            LibraryCatalogMapper.catalog(
                movieCategories = categoryRows.movies,
                seriesCategories = categoryRows.series,
                movies = mediaRows.movies,
                series = mediaRows.series,
                favorites = favorites,
                movieProgressRows = progressRows.movies,
                episodeProgressRows = progressRows.episodes,
                continueWatchingSuppression = progressRows.suppression,
                completedDownloads = downloads,
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun observeMovie(
        sourceId: SourceId,
        movieId: String,
    ): Flow<LibraryMovieSummary?> =
        combine(
            dao.observeAvailableMovie(sourceId.value, movieId),
            dao.observeFavorites(sourceId.value),
        ) { movie, favorites ->
            movie?.let {
                LibraryCatalogMapper.movie(
                    entity = it,
                    favoriteIds = favorites.favoriteIds(LibraryContentKind.MOVIE),
                )
            }
        }

    override suspend fun loadMovieDetail(
        sourceId: SourceId,
        movieId: String,
    ): LibraryMovieDetailLoadResult = movieDetailLoader.load(sourceId, movieId)

    override fun observeSeriesDetail(
        sourceId: SourceId,
        seriesId: String,
    ): Flow<LibrarySeriesDetail?> =
        combine(
            dao.observeAvailableSeries(sourceId.value, seriesId),
            dao.observeAvailableEpisodes(sourceId.value, seriesId),
            dao.observeFavorites(sourceId.value),
        ) { series, episodes, favorites ->
            series?.let {
                LibraryCatalogMapper.seriesDetail(
                    seriesEntity = it,
                    episodes = episodes,
                    favoriteIds = favorites.favoriteIds(LibraryContentKind.SERIES),
                )
            }
        }

    override suspend fun loadSeriesMetadata(
        sourceId: SourceId,
        seriesId: String,
    ): LibrarySeriesMetadataLoadResult = detailRefresher.loadMetadata(sourceId, seriesId)

    override suspend fun refreshSeriesDetail(
        sourceId: SourceId,
        seriesId: String,
    ): LibraryDetailRefreshResult = detailRefresher.refresh(sourceId, seriesId)

    override suspend fun setFavorite(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
        favorite: Boolean,
    ): Boolean {
        if (contentId.isBlank()) return false

        val available = try {
            when (contentKind) {
                LibraryContentKind.MOVIE ->
                    dao.getAvailableMovie(sourceId.value, contentId) != null
                LibraryContentKind.SERIES ->
                    dao.getAvailableSeries(sourceId.value, contentId) != null
                LibraryContentKind.EPISODE ->
                    dao.getAvailableEpisode(sourceId.value, contentId) != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!available && favorite) return false

        return try {
            if (favorite) {
                dao.upsertFavorite(
                    MediaFavoriteEntity(
                        sourceId = sourceId.value,
                        mediaKind = contentKind.name,
                        contentId = contentId,
                        addedAt = nowEpochMs(),
                    ),
                )
            } else {
                dao.deleteFavorite(
                    sourceId = sourceId.value,
                    mediaKind = contentKind.name,
                    contentId = contentId,
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private data class CategoryRows(
        val movies: List<ProviderCategoryEntity>,
        val series: List<ProviderCategoryEntity>,
    )

    private data class MediaRows(
        val movies: List<MovieEntity>,
        val series: List<SeriesEntity>,
    )

    override suspend fun removeFromContinueWatching(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
    ): Boolean {
        if (
            contentId.isBlank() ||
            contentKind !in setOf(LibraryContentKind.MOVIE, LibraryContentKind.EPISODE)
        ) return false
        val row = try {
            dao.getPlaybackProgress(
                sourceId = sourceId.value,
                mediaKind = contentKind.name,
                contentId = contentId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false
        if (row.completed) return false

        return try {
            continueWatchingSuppressionStore.suppress(
                sourceId = sourceId,
                contentKind = contentKind,
                contentId = contentId,
                suppressedAt = maxOf(nowEpochMs(), row.updatedAt),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private data class ProgressRows(
        val movies: List<LibraryMovieProgressRow>,
        val episodes: List<LibraryEpisodeProgressRow>,
        val suppression: Map<LibraryContinueWatchingSuppressionKey, Long> = emptyMap(),
    )
}

internal object LibraryCatalogMapper {
    fun catalog(
        movieCategories: List<ProviderCategoryEntity>,
        seriesCategories: List<ProviderCategoryEntity>,
        movies: List<MovieEntity>,
        series: List<SeriesEntity>,
        favorites: List<MediaFavoriteEntity>,
        movieProgressRows: List<LibraryMovieProgressRow> = emptyList(),
        episodeProgressRows: List<LibraryEpisodeProgressRow> = emptyList(),
        continueWatchingSuppression: Map<LibraryContinueWatchingSuppressionKey, Long> = emptyMap(),
        completedDownloads: List<DownloadEntity> = emptyList(),
    ): LibraryCatalogSnapshot {
        val movieFavorites = favorites.favoriteIds(LibraryContentKind.MOVIE)
        val seriesFavorites = favorites.favoriteIds(LibraryContentKind.SERIES)
        return LibraryCatalogSnapshot(
            movieCategories = movieCategories.map(::category),
            seriesCategories = seriesCategories.map(::category),
            movies = movies.map { movie(it, movieFavorites) },
            series = series.map { series(it, seriesFavorites) },
            continueWatching = continueWatching(
                movieRows = movieProgressRows,
                episodeRows = episodeProgressRows,
                suppression = continueWatchingSuppression,
            ),
            downloadedMedia = downloadedMedia(completedDownloads),
        )
    }

    fun continueWatching(
        movieRows: List<LibraryMovieProgressRow>,
        episodeRows: List<LibraryEpisodeProgressRow>,
        suppression: Map<LibraryContinueWatchingSuppressionKey, Long> = emptyMap(),
    ): List<LibraryContinueWatchingItem> {
        val movies = movieRows
            .filter { row ->
                LibraryContinueWatchingPolicy.isEligible(row.positionMs, row.durationMs)
            }
            .filterNot { row ->
                suppression[
                    LibraryContinueWatchingSuppressionKey(
                        LibraryContentKind.MOVIE,
                        row.contentId,
                    )
                ]?.let { it >= row.updatedAt } == true
            }
            .map { row ->
                LibraryContinueWatchingItem(
                    contentKind = LibraryContentKind.MOVIE,
                    contentId = row.contentId,
                    title = row.title,
                    posterUrl = row.posterUrl,
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAt = row.updatedAt,
                )
            }
        val episodes = episodeRows
            .filter { row ->
                LibraryContinueWatchingPolicy.isEligible(row.positionMs, row.durationMs)
            }
            .filterNot { row ->
                suppression[
                    LibraryContinueWatchingSuppressionKey(
                        LibraryContentKind.EPISODE,
                        row.contentId,
                    )
                ]?.let { it >= row.updatedAt } == true
            }
            .groupBy(LibraryEpisodeProgressRow::seriesId)
            .values
            .mapNotNull { rows ->
                rows.maxWithOrNull(
                    compareBy<LibraryEpisodeProgressRow>(
                        LibraryEpisodeProgressRow::updatedAt,
                        LibraryEpisodeProgressRow::contentId,
                    ),
                )
            }
            .map { row ->
                LibraryContinueWatchingItem(
                    contentKind = LibraryContentKind.EPISODE,
                    contentId = row.contentId,
                    title = row.title,
                    seriesId = row.seriesId,
                    seriesTitle = row.seriesTitle,
                    seasonNumber = row.seasonNumber,
                    episodeNumber = row.episodeNumber,
                    posterUrl = row.posterUrl,
                    positionMs = row.positionMs,
                    durationMs = row.durationMs,
                    updatedAt = row.updatedAt,
                )
            }
        return (movies + episodes).sortedWith(
            compareByDescending<LibraryContinueWatchingItem> { it.updatedAt }
                .thenBy { it.contentKind.name }
                .thenBy { it.contentId },
        )
    }

    fun downloadedMedia(rows: List<DownloadEntity>): List<LibraryDownloadedMediaItem> {
        val seenContent = mutableSetOf<Pair<LibraryContentKind, String>>()
        return rows
            .asSequence()
            .sortedWith(
                compareByDescending<DownloadEntity> { it.updatedAt }
                    .thenBy { it.downloadId },
            )
            .mapNotNull { row ->
                val kind = runCatching { LibraryContentKind.valueOf(row.mediaKind) }.getOrNull()
                    ?.takeIf { it == LibraryContentKind.MOVIE || it == LibraryContentKind.EPISODE }
                    ?: return@mapNotNull null
                if (
                    row.state != COMPLETED_DOWNLOAD_STATE ||
                    row.localReference.isNullOrBlank() ||
                    row.bytesDownloaded <= 0L ||
                    row.contentId.isBlank() ||
                    row.title.isBlank()
                ) {
                    return@mapNotNull null
                }
                if (!seenContent.add(kind to row.contentId)) return@mapNotNull null

                LibraryDownloadedMediaItem(
                    downloadId = row.downloadId,
                    contentKind = kind,
                    contentId = row.contentId,
                    title = row.title,
                    bytesDownloaded = row.bytesDownloaded,
                    totalBytes = row.totalBytes,
                    updatedAt = row.updatedAt,
                )
            }
            .toList()
    }

    fun movie(
        entity: MovieEntity,
        favoriteIds: Set<String>,
    ): LibraryMovieSummary = LibraryMovieSummary(
        movieId = entity.movieId,
        categoryId = entity.categoryKey,
        title = entity.name,
        posterUrl = entity.posterUrl,
        backdropUrl = entity.backdropUrl,
        rating = entity.rating,
        providerOrder = entity.providerOrder,
        favorite = entity.movieId in favoriteIds,
    )

    fun series(
        entity: SeriesEntity,
        favoriteIds: Set<String>,
    ): LibrarySeriesSummary = LibrarySeriesSummary(
        seriesId = entity.seriesId,
        categoryId = entity.categoryKey,
        title = entity.name,
        posterUrl = entity.posterUrl,
        backdropUrl = entity.backdropUrl,
        description = entity.description,
        rating = entity.rating,
        providerOrder = entity.providerOrder,
        favorite = entity.seriesId in favoriteIds,
    )

    fun seriesDetail(
        seriesEntity: SeriesEntity,
        episodes: List<EpisodeEntity>,
        favoriteIds: Set<String>,
    ): LibrarySeriesDetail {
        val episodeRows = episodes.map { episode ->
            LibraryEpisodeSummary(
                episodeId = episode.episodeId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                title = episode.title,
                durationMs = episode.durationMs,
            )
        }
        return LibrarySeriesDetail(
            series = series(seriesEntity, favoriteIds),
            seasons = episodeRows
                .groupBy(LibraryEpisodeSummary::seasonNumber)
                .toSortedMap()
                .map { (seasonNumber, seasonEpisodes) ->
                    LibrarySeason(
                        seasonNumber = seasonNumber,
                        episodes = seasonEpisodes.sortedWith(
                            compareBy<LibraryEpisodeSummary>(
                                LibraryEpisodeSummary::episodeNumber,
                                LibraryEpisodeSummary::episodeId,
                            ),
                        ),
                    )
                },
        )
    }

    private fun category(entity: ProviderCategoryEntity): LibraryCategory = LibraryCategory(
        categoryId = entity.categoryKey,
        displayName = entity.name,
        providerOrder = entity.providerOrder,
    )

    private const val COMPLETED_DOWNLOAD_STATE = "COMPLETED"
}

private fun List<MediaFavoriteEntity>.favoriteIds(kind: LibraryContentKind): Set<String> =
    asSequence()
        .filter { it.mediaKind == kind.name }
        .map(MediaFavoriteEntity::contentId)
        .toSet()
