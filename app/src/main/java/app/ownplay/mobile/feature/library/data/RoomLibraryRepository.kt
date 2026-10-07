package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.CategoryPersonalizationEntity
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.data.db.EpisodeEntity
import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.LibraryEpisodeProgressRow
import app.ownplay.mobile.data.db.LibraryMovieProgressRow
import app.ownplay.mobile.data.db.LibraryTitlePersonalizationEntity
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
import app.ownplay.mobile.feature.library.domain.LibraryManagementCategory
import app.ownplay.mobile.feature.library.domain.LibraryManagementSnapshot
import app.ownplay.mobile.feature.library.domain.LibraryManagementTitle
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

        val personalization = combine(
            dao.observeLibraryCategoryPersonalization(sourceId.value),
            dao.observeLibraryTitlePersonalization(sourceId.value),
        ) { categoryRows, titleRows ->
            PersonalizationRows(categoryRows, titleRows)
        }

        val presentation = combine(
            categories,
            media,
            dao.observeFavorites(sourceId.value),
        ) { categoryRows, mediaRows, favorites ->
            PresentationRows(categoryRows, mediaRows, favorites)
        }

        return combine(
            presentation,
            personalization,
            progress,
            dao.observeCompletedDownloads(sourceId.value),
        ) { presentationRows, personalizationRows, progressRows, downloads ->
            LibraryCatalogMapper.catalog(
                movieCategories = presentationRows.categories.movies,
                seriesCategories = presentationRows.categories.series,
                movies = presentationRows.media.movies,
                series = presentationRows.media.series,
                favorites = presentationRows.favorites,
                categoryPersonalization = personalizationRows.categories,
                titlePersonalization = personalizationRows.titles,
                movieProgressRows = progressRows.movies,
                episodeProgressRows = progressRows.episodes,
                continueWatchingSuppression = progressRows.suppression,
                completedDownloads = downloads,
            )
        }.flowOn(Dispatchers.Default)
    }

    override fun observeManagement(sourceId: SourceId): Flow<LibraryManagementSnapshot> {
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
        val personalization = combine(
            dao.observeLibraryCategoryPersonalization(sourceId.value),
            dao.observeLibraryTitlePersonalization(sourceId.value),
        ) { categoryRows, titleRows ->
            PersonalizationRows(categoryRows, titleRows)
        }
        return combine(categories, media, personalization) { categoryRows, mediaRows, personalizationRows ->
            LibraryCatalogMapper.management(
                movieCategories = categoryRows.movies,
                seriesCategories = categoryRows.series,
                movies = mediaRows.movies,
                series = mediaRows.series,
                categoryPersonalization = personalizationRows.categories,
                titlePersonalization = personalizationRows.titles,
            )
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun setLibraryCategoryHidden(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        categoryId: String,
        hidden: Boolean,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind() || categoryId.isBlank()) return@libraryMutation false
        val availableIds = dao.getAvailableLibraryCategoryIds(sourceId.value, contentKind.name)
        if (categoryId !in availableIds) return@libraryMutation false
        val current = dao.getLibraryCategoryPersonalization(sourceId.value, contentKind.name, categoryId)
        dao.upsertLibraryCategoryPersonalization(
            listOf(
                (current ?: CategoryPersonalizationEntity(
                    sourceId = sourceId.value,
                    kind = contentKind.name,
                    categoryKey = categoryId,
                )).copy(hidden = hidden),
            ),
        )
        true
    }

    override suspend fun setLibraryCategoryOrder(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        orderedCategoryIds: List<String>,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind()) return@libraryMutation false
        val ordered = orderedCategoryIds.filter(String::isNotBlank).distinct()
        val available = dao.getAvailableLibraryCategoryIds(sourceId.value, contentKind.name)
        if (ordered.size != available.size || ordered.toSet() != available.toSet()) return@libraryMutation false
        val existing = dao.getLibraryCategoryPersonalizationForKind(sourceId.value, contentKind.name)
            .associateBy(CategoryPersonalizationEntity::categoryKey)
        dao.upsertLibraryCategoryPersonalization(
            ordered.mapIndexed { index, categoryId ->
                (existing[categoryId] ?: CategoryPersonalizationEntity(
                    sourceId = sourceId.value,
                    kind = contentKind.name,
                    categoryKey = categoryId,
                )).copy(manualOrder = index)
            },
        )
        true
    }

    override suspend fun resetLibraryCategoryOrder(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind()) return@libraryMutation false
        val available = dao.getAvailableLibraryCategoryIds(sourceId.value, contentKind.name).toSet()
        if (available.isEmpty()) return@libraryMutation false
        val changed = dao.getLibraryCategoryPersonalizationForKind(sourceId.value, contentKind.name)
            .filter { it.categoryKey in available && it.manualOrder != null }
            .map { it.copy(manualOrder = null) }
        if (changed.isNotEmpty()) dao.upsertLibraryCategoryPersonalization(changed)
        true
    }

    override suspend fun setLibraryTitleHidden(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        contentId: String,
        hidden: Boolean,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind() || contentId.isBlank()) return@libraryMutation false
        val available = when (contentKind) {
            LibraryContentKind.MOVIE -> dao.getAvailableMovie(sourceId.value, contentId) != null
            LibraryContentKind.SERIES -> dao.getAvailableSeries(sourceId.value, contentId) != null
            LibraryContentKind.EPISODE -> false
        }
        if (!available) return@libraryMutation false
        val current = dao.getLibraryTitlePersonalization(sourceId.value, contentKind.name, contentId)
        dao.upsertLibraryTitlePersonalization(
            listOf(
                (current ?: LibraryTitlePersonalizationEntity(
                    sourceId = sourceId.value,
                    mediaKind = contentKind.name,
                    contentId = contentId,
                )).copy(hidden = hidden),
            ),
        )
        true
    }

    override suspend fun setLibraryTitleOrder(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        categoryId: String?,
        orderedContentIds: List<String>,
        allTitles: Boolean,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind()) return@libraryMutation false
        val ordered = orderedContentIds.filter(String::isNotBlank).distinct()
        val available = availableLibraryTitleIds(sourceId, contentKind, categoryId, allTitles)
        if (ordered.size != available.size || ordered.toSet() != available.toSet()) return@libraryMutation false
        val existing = dao.getLibraryTitlePersonalizationForKind(sourceId.value, contentKind.name)
            .associateBy(LibraryTitlePersonalizationEntity::contentId)
        dao.upsertLibraryTitlePersonalization(
            ordered.mapIndexed { index, contentId ->
                (existing[contentId] ?: LibraryTitlePersonalizationEntity(
                    sourceId = sourceId.value,
                    mediaKind = contentKind.name,
                    contentId = contentId,
                )).copy(manualOrder = index)
            },
        )
        true
    }

    override suspend fun resetLibraryTitleOrder(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        categoryId: String?,
        allTitles: Boolean,
    ): Boolean = libraryMutation {
        if (!contentKind.isManageableLibraryKind()) return@libraryMutation false
        val available = availableLibraryTitleIds(sourceId, contentKind, categoryId, allTitles).toSet()
        if (available.isEmpty()) return@libraryMutation false
        val changed = dao.getLibraryTitlePersonalizationForKind(sourceId.value, contentKind.name)
            .filter { it.contentId in available && it.manualOrder != null }
            .map { it.copy(manualOrder = null) }
        if (changed.isNotEmpty()) dao.upsertLibraryTitlePersonalization(changed)
        true
    }

    private suspend fun availableLibraryTitleIds(
        sourceId: SourceId,
        contentKind: LibraryContentKind,
        categoryId: String?,
        allTitles: Boolean,
    ): List<String> = when (contentKind) {
        LibraryContentKind.MOVIE -> dao.getAvailableMovieIdsForManagement(sourceId.value, categoryId, allTitles)
        LibraryContentKind.SERIES -> dao.getAvailableSeriesIdsForManagement(sourceId.value, categoryId, allTitles)
        LibraryContentKind.EPISODE -> emptyList()
    }

    private suspend fun libraryMutation(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
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

    private data class PersonalizationRows(
        val categories: List<CategoryPersonalizationEntity>,
        val titles: List<LibraryTitlePersonalizationEntity>,
    )

    private data class PresentationRows(
        val categories: CategoryRows,
        val media: MediaRows,
        val favorites: List<MediaFavoriteEntity>,
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
        categoryPersonalization: List<CategoryPersonalizationEntity> = emptyList(),
        titlePersonalization: List<LibraryTitlePersonalizationEntity> = emptyList(),
        movieProgressRows: List<LibraryMovieProgressRow> = emptyList(),
        episodeProgressRows: List<LibraryEpisodeProgressRow> = emptyList(),
        continueWatchingSuppression: Map<LibraryContinueWatchingSuppressionKey, Long> = emptyMap(),
        completedDownloads: List<DownloadEntity> = emptyList(),
    ): LibraryCatalogSnapshot {
        val categoryStates = categoryPersonalization.associateBy { it.kind to it.categoryKey }
        val titleStates = titlePersonalization.associateBy { it.mediaKind to it.contentId }
        val movieCategoryOrder = categoryPresentationRanks(movieCategories, LibraryContentKind.MOVIE, categoryStates)
        val seriesCategoryOrder = categoryPresentationRanks(seriesCategories, LibraryContentKind.SERIES, categoryStates)
        val movieOrder = titlePresentationRanks(movies, LibraryContentKind.MOVIE, titleStates, MovieEntity::movieId, MovieEntity::categoryKey, MovieEntity::providerOrder)
        val seriesOrder = titlePresentationRanks(series, LibraryContentKind.SERIES, titleStates, SeriesEntity::seriesId, SeriesEntity::categoryKey, SeriesEntity::providerOrder)
        val hiddenMovieCategories = categoryStates.asSequence()
            .filter { (key, state) -> key.first == LibraryContentKind.MOVIE.name && state.hidden }
            .map { it.key.second }
            .toSet()
        val hiddenSeriesCategories = categoryStates.asSequence()
            .filter { (key, state) -> key.first == LibraryContentKind.SERIES.name && state.hidden }
            .map { it.key.second }
            .toSet()
        val visibleMovies = movies.filter { entity ->
            entity.categoryKey !in hiddenMovieCategories && titleStates[LibraryContentKind.MOVIE.name to entity.movieId]?.hidden != true
        }
        val visibleSeries = series.filter { entity ->
            entity.categoryKey !in hiddenSeriesCategories && titleStates[LibraryContentKind.SERIES.name to entity.seriesId]?.hidden != true
        }
        val movieFavorites = favorites.favoriteIds(LibraryContentKind.MOVIE)
        val seriesFavorites = favorites.favoriteIds(LibraryContentKind.SERIES)
        val visibleMovieIds = visibleMovies.mapTo(hashSetOf(), MovieEntity::movieId)
        val visibleSeriesIds = visibleSeries.mapTo(hashSetOf(), SeriesEntity::seriesId)
        return LibraryCatalogSnapshot(
            movieCategories = movieCategories
                .filterNot { categoryStates[LibraryContentKind.MOVIE.name to it.categoryKey]?.hidden == true }
                .sortedBy { movieCategoryOrder[it.categoryKey] ?: Int.MAX_VALUE }
                .map { category(it, movieCategoryOrder[it.categoryKey] ?: it.providerOrder) },
            seriesCategories = seriesCategories
                .filterNot { categoryStates[LibraryContentKind.SERIES.name to it.categoryKey]?.hidden == true }
                .sortedBy { seriesCategoryOrder[it.categoryKey] ?: Int.MAX_VALUE }
                .map { category(it, seriesCategoryOrder[it.categoryKey] ?: it.providerOrder) },
            movies = visibleMovies
                .sortedBy { movieOrder[it.movieId] ?: Int.MAX_VALUE }
                .map { movie(it, movieFavorites, movieOrder[it.movieId] ?: it.providerOrder) },
            series = visibleSeries
                .sortedBy { seriesOrder[it.seriesId] ?: Int.MAX_VALUE }
                .map { series(it, seriesFavorites, seriesOrder[it.seriesId] ?: it.providerOrder) },
            continueWatching = continueWatching(
                movieRows = movieProgressRows,
                episodeRows = episodeProgressRows,
                suppression = continueWatchingSuppression,
            ).filter { item ->
                when (item.contentKind) {
                    LibraryContentKind.MOVIE -> item.contentId in visibleMovieIds
                    LibraryContentKind.EPISODE -> item.seriesId in visibleSeriesIds
                    LibraryContentKind.SERIES -> false
                }
            },
            downloadedMedia = downloadedMedia(completedDownloads),
        )
    }

    fun management(
        movieCategories: List<ProviderCategoryEntity>,
        seriesCategories: List<ProviderCategoryEntity>,
        movies: List<MovieEntity>,
        series: List<SeriesEntity>,
        categoryPersonalization: List<CategoryPersonalizationEntity>,
        titlePersonalization: List<LibraryTitlePersonalizationEntity>,
    ): LibraryManagementSnapshot {
        val categoryStates = categoryPersonalization.associateBy { it.kind to it.categoryKey }
        val titleStates = titlePersonalization.associateBy { it.mediaKind to it.contentId }
        val movieCategoryOrder = categoryPresentationRanks(movieCategories, LibraryContentKind.MOVIE, categoryStates)
        val seriesCategoryOrder = categoryPresentationRanks(seriesCategories, LibraryContentKind.SERIES, categoryStates)
        val movieOrder = titlePresentationRanks(movies, LibraryContentKind.MOVIE, titleStates, MovieEntity::movieId, MovieEntity::categoryKey, MovieEntity::providerOrder)
        val seriesOrder = titlePresentationRanks(series, LibraryContentKind.SERIES, titleStates, SeriesEntity::seriesId, SeriesEntity::categoryKey, SeriesEntity::providerOrder)
        return LibraryManagementSnapshot(
            categories = buildList {
                addAll(movieCategories.sortedBy { movieCategoryOrder[it.categoryKey] ?: Int.MAX_VALUE }.map { entity ->
                    val state = categoryStates[LibraryContentKind.MOVIE.name to entity.categoryKey]
                    LibraryManagementCategory(
                        contentKind = LibraryContentKind.MOVIE,
                        categoryId = entity.categoryKey,
                        displayName = entity.name,
                        providerOrder = entity.providerOrder,
                        hidden = state?.hidden ?: false,
                        manualOrder = state?.manualOrder,
                    )
                })
                addAll(seriesCategories.sortedBy { seriesCategoryOrder[it.categoryKey] ?: Int.MAX_VALUE }.map { entity ->
                    val state = categoryStates[LibraryContentKind.SERIES.name to entity.categoryKey]
                    LibraryManagementCategory(
                        contentKind = LibraryContentKind.SERIES,
                        categoryId = entity.categoryKey,
                        displayName = entity.name,
                        providerOrder = entity.providerOrder,
                        hidden = state?.hidden ?: false,
                        manualOrder = state?.manualOrder,
                    )
                })
            },
            titles = buildList {
                addAll(movies.sortedBy { movieOrder[it.movieId] ?: Int.MAX_VALUE }.map { entity ->
                    val state = titleStates[LibraryContentKind.MOVIE.name to entity.movieId]
                    LibraryManagementTitle(
                        contentKind = LibraryContentKind.MOVIE,
                        contentId = entity.movieId,
                        categoryId = entity.categoryKey,
                        title = entity.name,
                        providerOrder = entity.providerOrder,
                        hidden = state?.hidden ?: false,
                        manualOrder = state?.manualOrder,
                    )
                })
                addAll(series.sortedBy { seriesOrder[it.seriesId] ?: Int.MAX_VALUE }.map { entity ->
                    val state = titleStates[LibraryContentKind.SERIES.name to entity.seriesId]
                    LibraryManagementTitle(
                        contentKind = LibraryContentKind.SERIES,
                        contentId = entity.seriesId,
                        categoryId = entity.categoryKey,
                        title = entity.name,
                        providerOrder = entity.providerOrder,
                        hidden = state?.hidden ?: false,
                        manualOrder = state?.manualOrder,
                    )
                })
            },
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
        presentationOrder: Int = entity.providerOrder,
    ): LibraryMovieSummary = LibraryMovieSummary(
        movieId = entity.movieId,
        categoryId = entity.categoryKey,
        title = entity.name,
        posterUrl = entity.posterUrl,
        backdropUrl = entity.backdropUrl,
        rating = entity.rating,
        providerOrder = entity.providerOrder,
        presentationOrder = presentationOrder,
        favorite = entity.movieId in favoriteIds,
    )

    fun series(
        entity: SeriesEntity,
        favoriteIds: Set<String>,
        presentationOrder: Int = entity.providerOrder,
    ): LibrarySeriesSummary = LibrarySeriesSummary(
        seriesId = entity.seriesId,
        categoryId = entity.categoryKey,
        title = entity.name,
        posterUrl = entity.posterUrl,
        backdropUrl = entity.backdropUrl,
        description = entity.description,
        rating = entity.rating,
        providerOrder = entity.providerOrder,
        presentationOrder = presentationOrder,
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

    private fun category(
        entity: ProviderCategoryEntity,
        presentationOrder: Int = entity.providerOrder,
    ): LibraryCategory = LibraryCategory(
        categoryId = entity.categoryKey,
        displayName = entity.name,
        providerOrder = entity.providerOrder,
        presentationOrder = presentationOrder,
    )

    private fun categoryPresentationRanks(
        rows: List<ProviderCategoryEntity>,
        kind: LibraryContentKind,
        states: Map<Pair<String, String>, CategoryPersonalizationEntity>,
    ): Map<String, Int> = rows
        .sortedWith(
            compareBy<ProviderCategoryEntity> { states[kind.name to it.categoryKey]?.manualOrder ?: Int.MAX_VALUE }
                .thenBy(ProviderCategoryEntity::providerOrder)
                .thenBy(ProviderCategoryEntity::categoryKey),
        )
        .mapIndexed { index, row -> row.categoryKey to index }
        .toMap()

    private fun <T> titlePresentationRanks(
        rows: List<T>,
        kind: LibraryContentKind,
        states: Map<Pair<String, String>, LibraryTitlePersonalizationEntity>,
        id: (T) -> String,
        categoryId: (T) -> String?,
        providerOrder: (T) -> Int,
    ): Map<String, Int> {
        val native = rows.sortedWith(compareBy<T>(providerOrder).thenBy(id))
        val byScope = native.groupBy(categoryId)
        val replacementByScope = byScope.mapValues { (_, scopedRows) ->
            scopedRows.sortedWith(
                compareBy<T> { states[kind.name to id(it)]?.manualOrder ?: Int.MAX_VALUE }
                    .thenBy(providerOrder)
                    .thenBy(id),
            ).iterator()
        }
        val reordered = native.map { row -> replacementByScope[categoryId(row)]?.next() ?: row }
        return reordered.mapIndexed { index, row -> id(row) to index }.toMap()
    }

    private const val COMPLETED_DOWNLOAD_STATE = "COMPLETED"
}

private fun LibraryContentKind.isManageableLibraryKind(): Boolean =
    this == LibraryContentKind.MOVIE || this == LibraryContentKind.SERIES

private fun List<MediaFavoriteEntity>.favoriteIds(kind: LibraryContentKind): Set<String> =
    asSequence()
        .filter { it.mediaKind == kind.name }
        .map(MediaFavoriteEntity::contentId)
        .toSet()
