package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.M3uClientException
import app.ownplay.mobile.sources.data.m3u.M3uClientFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamClientException
import app.ownplay.mobile.sources.data.xtream.XtreamClientFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamCategory
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStream
import app.ownplay.mobile.sources.data.xtream.XtreamLiveCategoryAttribution
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStreamIdentity
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceType
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class DefaultSourceCatalogLoader(
    private val xtreamClient: XtreamClient,
    private val m3uClient: M3uClient,
) : SourceCatalogLoader {
    override suspend fun load(
        sourceId: SourceId,
        sourceType: SourceType,
        baseLocator: String,
        secret: SourceSecret,
    ): ProviderCatalogSnapshot = withContext(Dispatchers.Default) {
        try {
            when (sourceType) {
                SourceType.XTREAM -> loadXtream(sourceId, baseLocator, secret)
                SourceType.M3U -> loadM3u(sourceId, secret)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CatalogLoadException) {
            throw error
        } catch (error: XtreamClientException) {
            throw CatalogLoadException(error.category.toRefreshCategory(), error)
        } catch (error: M3uClientException) {
            throw CatalogLoadException(error.category.toRefreshCategory(), error)
        } catch (error: ProviderTransportException) {
            throw CatalogLoadException(error.toRefreshCategory(), error)
        } catch (error: Exception) {
            throw CatalogLoadException(SourceRefreshFailureCategory.UNKNOWN, error)
        }
    }

    private suspend fun loadXtream(
        sourceId: SourceId,
        baseLocator: String,
        secret: SourceSecret,
    ): ProviderCatalogSnapshot = coroutineScope {
        val credential = secret as? SourceSecret.Xtream
            ?: throw CatalogLoadException(SourceRefreshFailureCategory.AUTHENTICATION)
        val connection = XtreamConnection(
            baseUrl = baseLocator,
            username = credential.username,
            password = credential.password,
        )

        val accountInfoDeferred = async { loadSection { xtreamClient.accountInfo(connection) } }
        val liveCategoriesDeferred = async { loadSection { xtreamClient.liveCategories(connection) } }
        val liveStreamsDeferred = async { loadSection { xtreamClient.liveStreams(connection) } }
        val movieCategoriesDeferred = async { loadSection { xtreamClient.movieCategories(connection) } }
        val moviesDeferred = async { loadSection { xtreamClient.movies(connection) } }
        val seriesCategoriesDeferred = async { loadSection { xtreamClient.seriesCategories(connection) } }
        val seriesDeferred = async { loadSection { xtreamClient.series(connection) } }

        val accountInfo = accountInfoDeferred.await()
        accountInfo.error
            ?.takeIf { error ->
                error.refreshCategory() == SourceRefreshFailureCategory.AUTHENTICATION
            }
            ?.let { error ->
                throw CatalogLoadException(SourceRefreshFailureCategory.AUTHENTICATION, error)
            }
        val liveCategories = liveCategoriesDeferred.await()
        val globalLiveStreams = liveStreamsDeferred.await()
        val liveStreams = recoverLiveCategoryAttribution(
            connection = connection,
            categories = liveCategories,
            streams = globalLiveStreams,
        )
        val movieCategories = movieCategoriesDeferred.await()
        val movies = moviesDeferred.await()
        val seriesCategories = seriesCategoriesDeferred.await()
        val series = seriesDeferred.await()

        val coreSections = listOf(liveCategories, liveStreams, movieCategories, movies, seriesCategories, series)
        coreSections.mapNotNull(SectionLoad<*>::error).firstOrNull { error ->
            error.refreshCategory() == SourceRefreshFailureCategory.AUTHENTICATION
        }?.let { error ->
            throw CatalogLoadException(SourceRefreshFailureCategory.AUTHENTICATION, error)
        }
        if (coreSections.none(SectionLoad<*>::isSuccess)) {
            val failures = coreSections.mapNotNull(SectionLoad<*>::error)
            val category = failures
                .map { error -> error.refreshCategory() }
                .minByOrNull(::refreshFailurePriority)
                ?: SourceRefreshFailureCategory.UNKNOWN
            throw CatalogLoadException(category, failures.firstOrNull())
        }

        val preferredLiveExtension = XtreamLiveStreamIdentity.preferredSupportedExtension(
            accountInfo.valueOrNull()?.allowedOutputFormats.orEmpty(),
        )
        val authoritative = buildSet {
            if (liveCategories.isSuccess) add(CatalogSection.LIVE_CATEGORIES)
            if (liveStreams.isSuccess) add(CatalogSection.LIVE_CHANNELS)
            if (movieCategories.isSuccess) add(CatalogSection.MOVIE_CATEGORIES)
            if (movies.isSuccess) add(CatalogSection.MOVIES)
            if (seriesCategories.isSuccess) add(CatalogSection.SERIES_CATEGORIES)
            if (series.isSuccess) add(CatalogSection.SERIES)
        }

        ProviderCatalogSnapshot(
            sourceType = SourceType.XTREAM,
            categories = buildList {
                addAll(liveCategories.valueOrNull().orEmpty().map { category ->
                    ProviderCategoryRecord(
                        kind = KIND_LIVE,
                        providerKey = category.providerCategoryId,
                        name = category.name,
                        providerOrder = category.providerOrder,
                    )
                })
                addAll(movieCategories.valueOrNull().orEmpty().map { category ->
                    ProviderCategoryRecord(
                        kind = KIND_MOVIE,
                        providerKey = category.providerCategoryId,
                        name = category.name,
                        providerOrder = category.providerOrder,
                    )
                })
                addAll(seriesCategories.valueOrNull().orEmpty().map { category ->
                    ProviderCategoryRecord(
                        kind = KIND_SERIES,
                        providerKey = category.providerCategoryId,
                        name = category.name,
                        providerOrder = category.providerOrder,
                    )
                })
            }.distinctBy { it.kind to it.providerKey },
            liveChannels = liveStreams.valueOrNull().orEmpty().map { stream ->
                ProviderLiveChannelRecord(
                    proposedChannelId = StableIdentity.xtreamLiveChannel(sourceId, stream.streamId),
                    providerKey = stream.streamId,
                    providerStreamId = stream.streamId,
                    categoryProviderKey = stream.categoryId,
                    name = stream.name,
                    tvgId = stream.tvgId,
                    tvgName = stream.name,
                    logoUrl = stream.logoUrl,
                    streamLocator = XtreamLiveStreamIdentity.encode(
                        streamId = stream.streamId,
                        containerExtension = stream.containerExtension ?: preferredLiveExtension,
                    ),
                    providerOrder = stream.providerOrder,
                )
            }.distinctBy(ProviderLiveChannelRecord::proposedChannelId),
            movies = movies.valueOrNull().orEmpty().map { movie ->
                ProviderMovieRecord(
                    proposedMovieId = StableIdentity.xtreamMovie(sourceId, movie.streamId),
                    providerStreamId = movie.streamId,
                    categoryProviderKey = movie.categoryId,
                    name = movie.name,
                    posterUrl = movie.posterUrl,
                    backdropUrl = null,
                    extension = movie.containerExtension,
                    rating = movie.rating,
                    providerOrder = movie.providerOrder,
                )
            }.distinctBy(ProviderMovieRecord::proposedMovieId),
            series = series.valueOrNull().orEmpty().map { item ->
                ProviderSeriesRecord(
                    proposedSeriesId = StableIdentity.xtreamSeries(sourceId, item.seriesId),
                    providerSeriesId = item.seriesId,
                    categoryProviderKey = item.categoryId,
                    name = item.name,
                    posterUrl = item.posterUrl,
                    backdropUrl = null,
                    description = item.description,
                    rating = item.rating,
                    providerOrder = item.providerOrder,
                )
            }.distinctBy(ProviderSeriesRecord::proposedSeriesId),
            authoritativeSections = authoritative,
        )
    }

    private suspend fun loadM3u(
        sourceId: SourceId,
        secret: SourceSecret,
    ): ProviderCatalogSnapshot {
        val credential = secret as? SourceSecret.M3uRemote
            ?: throw CatalogLoadException(SourceRefreshFailureCategory.AUTHENTICATION)
        val parsed = m3uClient.fetch(credential.playlistUrl)
        if (parsed.entries.isEmpty()) {
            throw CatalogLoadException(SourceRefreshFailureCategory.INVALID_PAYLOAD)
        }

        val normalizedEntries = parsed.entries.map { entry ->
            val group = entry.groupTitle?.trim()?.takeIf(String::isNotBlank)
            Triple(entry, group, entry.streamUrl.trim())
        }
        val tvgCounts = normalizedEntries
            .mapNotNull { (entry, _, _) -> entry.tvgId?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
        val stableLocatorCounts = normalizedEntries
            .map { (_, _, locator) ->
                M3uIdentityReconciliation.stableLocatorIdentity(locator)
            }
            .groupingBy { it }
            .eachCount()

        val categories = linkedMapOf<String, Int>()
        normalizedEntries.forEach { (_, group, _) ->
            if (group != null && group !in categories) categories[group] = categories.size
        }

        val channels = normalizedEntries.mapIndexed { index, (entry, group, locator) ->
            val stableTvgId = entry.tvgId?.trim()?.takeIf { tvgCounts[it] == 1 }
            val normalizedLocatorIdentity =
                M3uIdentityReconciliation.stableLocatorIdentity(locator)
            val proposedChannelId = StableIdentity.m3uLiveChannel(
                sourceId = sourceId,
                tvgId = stableTvgId,
                stableLocatorHint = locator.takeIf {
                    stableLocatorCounts[normalizedLocatorIdentity] == 1
                },
                normalizedName = entry.name,
                normalizedGroup = group ?: LEGACY_UNGROUPED_IDENTITY_GROUP,
                stableMetadataHint = entry.tvgName,
            )
            ProviderLiveChannelRecord(
                proposedChannelId = proposedChannelId,
                providerKey = proposedChannelId,
                providerStreamId = null,
                categoryProviderKey = group,
                name = entry.name,
                tvgId = entry.tvgId,
                tvgName = entry.tvgName,
                logoUrl = entry.logoUrl,
                streamLocator = locator,
                providerOrder = index,
            )
        }.distinctBy(ProviderLiveChannelRecord::proposedChannelId)

        return ProviderCatalogSnapshot(
            sourceType = SourceType.M3U,
            categories = categories.map { (name, order) ->
                ProviderCategoryRecord(
                    kind = KIND_LIVE,
                    providerKey = name,
                    name = name,
                    providerOrder = order,
                )
            },
            liveChannels = channels,
            movies = emptyList(),
            series = emptyList(),
        )
    }

    private suspend fun recoverLiveCategoryAttribution(
        connection: XtreamConnection,
        categories: SectionLoad<List<XtreamCategory>>,
        streams: SectionLoad<List<XtreamLiveStream>>,
    ): SectionLoad<List<XtreamLiveStream>> {
        if (!categories.isSuccess || !streams.isSuccess) return streams
        val categoryRows = categories.valueOrNull().orEmpty()
        val globalRows = streams.valueOrNull().orEmpty()
        val categoryIds = categoryRows
            .mapNotNull { XtreamLiveCategoryAttribution.normalizeProviderCategoryId(it.providerCategoryId) }
            .distinct()
        if (!XtreamLiveCategoryAttribution.needsRecovery(
                knownCategoryIds = categoryIds,
                streamCategoryIds = globalRows.map(XtreamLiveStream::categoryId),
            )
        ) {
            return streams
        }

        val gate = Semaphore(XTREAM_CATEGORY_RECOVERY_CONCURRENCY)
        val scopedMemberships = coroutineScope {
            categoryIds.map { categoryId ->
                async {
                    gate.withPermit {
                        val scoped = try {
                            xtreamClient.liveStreams(connection, categoryId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                        categoryId to scoped.map(XtreamLiveStream::streamId)
                    }
                }
            }.awaitAll()
        }
        val recovered = XtreamLiveCategoryAttribution.recoveredCategoryByStreamId(scopedMemberships)
        if (recovered.isEmpty()) return streams
        val known = categoryIds.toSet()
        return SectionLoad(
            value = globalRows.map { stream ->
                val existing = XtreamLiveCategoryAttribution
                    .normalizeProviderCategoryId(stream.categoryId)
                    ?.takeIf(known::contains)
                stream.copy(categoryId = existing ?: recovered[stream.streamId])
            },
        )
    }

    private data class SectionLoad<T>(
        val value: T? = null,
        val error: Throwable? = null,
    ) {
        val isSuccess: Boolean get() = error == null
        fun valueOrNull(): T? = if (isSuccess) value else null
    }

    private suspend fun <T> loadSection(block: suspend () -> T): SectionLoad<T> =
        try {
            SectionLoad(value = block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            SectionLoad(error = error)
        }

    private fun Throwable.refreshCategory(): SourceRefreshFailureCategory = when (this) {
        is CatalogLoadException -> category
        is XtreamClientException -> category.toRefreshCategory()
        is M3uClientException -> category.toRefreshCategory()
        is ProviderTransportException -> toRefreshCategory()
        else -> SourceRefreshFailureCategory.UNKNOWN
    }

    private fun refreshFailurePriority(category: SourceRefreshFailureCategory): Int = when (category) {
        SourceRefreshFailureCategory.AUTHENTICATION -> 0
        SourceRefreshFailureCategory.TIMEOUT -> 1
        SourceRefreshFailureCategory.NETWORK -> 2
        SourceRefreshFailureCategory.TRANSIENT_PROVIDER -> 3
        SourceRefreshFailureCategory.PROVIDER -> 4
        SourceRefreshFailureCategory.INVALID_PAYLOAD -> 5
        SourceRefreshFailureCategory.STORAGE -> 6
        SourceRefreshFailureCategory.UNKNOWN -> 7
    }

    private fun XtreamClientFailureCategory.toRefreshCategory(): SourceRefreshFailureCategory =
        when (this) {
            XtreamClientFailureCategory.AUTHENTICATION -> SourceRefreshFailureCategory.AUTHENTICATION
            XtreamClientFailureCategory.PROVIDER -> SourceRefreshFailureCategory.PROVIDER
            XtreamClientFailureCategory.TRANSIENT_PROVIDER -> SourceRefreshFailureCategory.TRANSIENT_PROVIDER
            XtreamClientFailureCategory.INVALID_PAYLOAD -> SourceRefreshFailureCategory.INVALID_PAYLOAD
        }

    private fun M3uClientFailureCategory.toRefreshCategory(): SourceRefreshFailureCategory =
        when (this) {
            M3uClientFailureCategory.AUTHENTICATION -> SourceRefreshFailureCategory.AUTHENTICATION
            M3uClientFailureCategory.PROVIDER -> SourceRefreshFailureCategory.PROVIDER
            M3uClientFailureCategory.TRANSIENT_PROVIDER -> SourceRefreshFailureCategory.TRANSIENT_PROVIDER
            M3uClientFailureCategory.INVALID_PAYLOAD -> SourceRefreshFailureCategory.INVALID_PAYLOAD
        }

    private fun ProviderTransportException.toRefreshCategory(): SourceRefreshFailureCategory {
        if (cause is SocketTimeoutException) return SourceRefreshFailureCategory.TIMEOUT
        return when (category) {
            ProviderTransportFailureCategory.INVALID_REQUEST -> SourceRefreshFailureCategory.INVALID_PAYLOAD
            ProviderTransportFailureCategory.NETWORK -> SourceRefreshFailureCategory.NETWORK
            ProviderTransportFailureCategory.RESPONSE_TOO_LARGE -> SourceRefreshFailureCategory.INVALID_PAYLOAD
            ProviderTransportFailureCategory.HTTP_ERROR -> SourceRefreshFailureCategory.PROVIDER
        }
    }

    companion object {
        const val KIND_LIVE = "LIVE"
        const val KIND_MOVIE = "MOVIE"
        const val KIND_SERIES = "SERIES"
        private const val LEGACY_UNGROUPED_IDENTITY_GROUP = "Other"
        private const val XTREAM_CATEGORY_RECOVERY_CONCURRENCY = 4
    }
}