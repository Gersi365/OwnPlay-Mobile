package app.ownplay.mobile.sources.data

import androidx.room.withTransaction
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.MovieEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.RefreshStateDao
import app.ownplay.mobile.data.db.RefreshStateEntity
import app.ownplay.mobile.data.db.SeriesEntity
import app.ownplay.mobile.feature.live.data.LiveOrganizationRefreshStore
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType

interface CatalogRefreshStore {
    suspend fun commitSuccessfulRefresh(
        sourceId: SourceId,
        sourceType: SourceType,
        generation: Long,
        snapshot: ProviderCatalogSnapshot,
        attemptAtEpochMs: Long,
        completedAtEpochMs: Long,
    )
}

class RoomCatalogRefreshStore(
    private val database: OwnPlayDatabase,
    private val refreshStateDao: RefreshStateDao,
    private val liveOrganizationRefreshStore: LiveOrganizationRefreshStore? = null,
) : CatalogRefreshStore {
    override suspend fun commitSuccessfulRefresh(
        sourceId: SourceId,
        sourceType: SourceType,
        generation: Long,
        snapshot: ProviderCatalogSnapshot,
        attemptAtEpochMs: Long,
        completedAtEpochMs: Long,
    ) {
        require(snapshot.sourceType == sourceType) { "Catalog source type mismatch" }
        database.withTransaction {
            val existingCategories = refreshStateDao.getCategoriesForRefresh(sourceId.value)
            val existingLiveChannels = refreshStateDao.getLiveChannelsForRefresh(sourceId.value)
            val existingMovies = refreshStateDao.getMoviesForRefresh(sourceId.value)
            val existingSeries = refreshStateDao.getSeriesForRefresh(sourceId.value)
            val authoritative = snapshot.authoritativeSections
            val plan = CatalogReconciler.reconcile(
                sourceId = sourceId,
                sourceType = sourceType,
                generation = generation,
                snapshot = snapshot,
                existingCategories = existingCategories,
                existingLiveChannels = existingLiveChannels,
                existingMovies = existingMovies,
                existingSeries = existingSeries,
            )

            refreshStateDao.upsertCategories(plan.categories)
            refreshStateDao.upsertLiveChannels(plan.liveChannels)
            if (
                CatalogSection.LIVE_CATEGORIES in authoritative &&
                CatalogSection.LIVE_CHANNELS in authoritative
            ) {
                refreshStateDao.markMissingCategoriesUnavailable(
                    sourceId = sourceId.value,
                    kind = DefaultSourceCatalogLoader.KIND_LIVE,
                    generation = generation,
                )
            }
            if (CatalogSection.LIVE_CHANNELS in authoritative) {
                refreshStateDao.markMissingLiveUnavailable(sourceId.value, generation)

                val organizationCategories = if (CatalogSection.LIVE_CATEGORIES in authoritative) {
                    plan.categories
                } else {
                    existingCategories.filter { it.kind == DefaultSourceCatalogLoader.KIND_LIVE }
                }
                liveOrganizationRefreshStore?.reconcileAutomatic(
                    sourceId = sourceId,
                    generation = generation,
                    providerCategories = organizationCategories,
                    liveChannels = plan.liveChannels,
                )
            }

            if (sourceType == SourceType.XTREAM) {
                refreshStateDao.upsertMovies(plan.movies)
                refreshStateDao.upsertSeries(plan.series)
                if (
                    CatalogSection.MOVIE_CATEGORIES in authoritative &&
                    CatalogSection.MOVIES in authoritative
                ) {
                    refreshStateDao.markMissingCategoriesUnavailable(
                        sourceId = sourceId.value,
                        kind = DefaultSourceCatalogLoader.KIND_MOVIE,
                        generation = generation,
                    )
                }
                if (
                    CatalogSection.SERIES_CATEGORIES in authoritative &&
                    CatalogSection.SERIES in authoritative
                ) {
                    refreshStateDao.markMissingCategoriesUnavailable(
                        sourceId = sourceId.value,
                        kind = DefaultSourceCatalogLoader.KIND_SERIES,
                        generation = generation,
                    )
                }
                if (CatalogSection.MOVIES in authoritative) {
                    refreshStateDao.markMissingMoviesUnavailable(sourceId.value, generation)
                }
                if (CatalogSection.SERIES in authoritative) {
                    refreshStateDao.markMissingSeriesUnavailable(sourceId.value, generation)
                }
            }

            refreshStateDao.upsert(
                RefreshStateEntity(
                    sourceId = sourceId.value,
                    generation = generation,
                    state = "SUCCESS",
                    lastAttempt = attemptAtEpochMs,
                    lastSuccess = completedAtEpochMs,
                    errorCode = null,
                ),
            )
        }
    }
}

data class CatalogWritePlan(
    val categories: List<ProviderCategoryEntity>,
    val liveChannels: List<LiveChannelEntity>,
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
)

object CatalogReconciler {
    fun reconcile(
        sourceId: SourceId,
        sourceType: SourceType,
        generation: Long,
        snapshot: ProviderCatalogSnapshot,
        existingCategories: List<ProviderCategoryEntity>,
        existingLiveChannels: List<LiveChannelEntity>,
        existingMovies: List<MovieEntity>,
        existingSeries: List<SeriesEntity>,
    ): CatalogWritePlan {
        require(snapshot.sourceType == sourceType) { "Catalog source type mismatch" }

        val existingCategoryKeyByProvider = existingCategories.associate {
            CategoryProviderIdentity(it.kind, it.providerKey) to it.categoryKey
        }
        val categoryKeyByProvider = existingCategoryKeyByProvider.toMutableMap().apply {
            snapshot.categories.forEach { record ->
                val identity = CategoryProviderIdentity(record.kind, record.providerKey)
                put(
                    identity,
                    existingCategoryKeyByProvider[identity]
                        ?: StableIdentity.providerCategory(sourceId, record.kind, record.providerKey),
                )
            }
        }

        val categories = snapshot.categories.map { record ->
            ProviderCategoryEntity(
                sourceId = sourceId.value,
                kind = record.kind,
                categoryKey = categoryKeyByProvider.getValue(
                    CategoryProviderIdentity(record.kind, record.providerKey),
                ),
                providerKey = record.providerKey,
                name = record.name,
                providerOrder = record.providerOrder,
                available = true,
                lastSeenGeneration = generation,
            )
        }

        val existingLiveByStreamId = existingLiveChannels
            .mapNotNull { row -> row.providerStreamId?.let { it to row.channelId } }
            .toMap()
        val existingLiveByProviderKey = existingLiveChannels.associate { it.providerKey to it.channelId }
        val reconciledM3uChannelIds = if (sourceType == SourceType.M3U) {
            M3uIdentityReconciliation.reconcileExistingChannelIds(
                incoming = snapshot.liveChannels,
                existing = existingLiveChannels,
                existingCategories = existingCategories,
            )
        } else {
            emptyMap()
        }

        val liveChannels = snapshot.liveChannels.map { record ->
            val channelId = when (sourceType) {
                SourceType.XTREAM -> record.providerStreamId
                    ?.let(existingLiveByStreamId::get)
                    ?: existingLiveByProviderKey[record.providerKey]
                    ?: record.proposedChannelId

                SourceType.M3U -> reconciledM3uChannelIds[record.proposedChannelId]
                    ?: existingLiveByProviderKey[record.providerKey]
                    ?: record.proposedChannelId
            }
            LiveChannelEntity(
                channelId = channelId,
                sourceId = sourceId.value,
                providerKey = record.providerKey,
                providerStreamId = record.providerStreamId,
                categoryKey = record.categoryProviderKey?.let { providerKey ->
                    categoryKeyByProvider[
                        CategoryProviderIdentity(DefaultSourceCatalogLoader.KIND_LIVE, providerKey)
                    ]
                },
                name = record.name,
                tvgId = record.tvgId,
                tvgName = record.tvgName,
                logoUrl = record.logoUrl,
                streamLocator = record.streamLocator,
                providerOrder = record.providerOrder,
                available = true,
                lastSeenGeneration = generation,
            )
        }

        val existingMovieByProviderId = existingMovies.associate {
            it.providerStreamId to it.movieId
        }
        val movies = snapshot.movies.map { record ->
            MovieEntity(
                movieId = existingMovieByProviderId[record.providerStreamId] ?: record.proposedMovieId,
                sourceId = sourceId.value,
                providerStreamId = record.providerStreamId,
                categoryKey = record.categoryProviderKey?.let { providerKey ->
                    categoryKeyByProvider[
                        CategoryProviderIdentity(DefaultSourceCatalogLoader.KIND_MOVIE, providerKey)
                    ]
                },
                name = record.name,
                posterUrl = record.posterUrl,
                backdropUrl = record.backdropUrl,
                extension = record.extension,
                rating = record.rating,
                providerOrder = record.providerOrder,
                available = true,
                lastSeenGeneration = generation,
            )
        }

        val existingSeriesByProviderId = existingSeries.associate {
            it.providerSeriesId to it.seriesId
        }
        val series = snapshot.series.map { record ->
            SeriesEntity(
                seriesId = existingSeriesByProviderId[record.providerSeriesId] ?: record.proposedSeriesId,
                sourceId = sourceId.value,
                providerSeriesId = record.providerSeriesId,
                categoryKey = record.categoryProviderKey?.let { providerKey ->
                    categoryKeyByProvider[
                        CategoryProviderIdentity(DefaultSourceCatalogLoader.KIND_SERIES, providerKey)
                    ]
                },
                name = record.name,
                posterUrl = record.posterUrl,
                backdropUrl = record.backdropUrl,
                description = record.description,
                rating = record.rating,
                providerOrder = record.providerOrder,
                available = true,
                lastSeenGeneration = generation,
            )
        }

        return CatalogWritePlan(
            categories = categories,
            liveChannels = liveChannels,
            movies = movies,
            series = series,
        )
    }

    private data class CategoryProviderIdentity(
        val kind: String,
        val providerKey: String,
    )
}
