package app.ownplay.player.series

import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.reconcile.CatalogRefreshGeneration
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.persistence.series.EpisodeProgressRow
import app.ownplay.player.persistence.series.ProviderSeriesCategoryEntity
import app.ownplay.player.persistence.series.ProviderSeriesEntity
import app.ownplay.player.persistence.series.ProviderSeriesEpisodeEntity
import app.ownplay.player.persistence.series.ProviderSeriesSeasonEntity
import app.ownplay.player.persistence.series.SeriesMediaKinds
import app.ownplay.player.persistence.series.SeriesRow
import app.ownplay.player.persistence.vod.MediaFavoriteEntity
import app.ownplay.player.persistence.vod.PlaybackProgressEntity
import app.ownplay.player.playback.PlaybackProgressPolicy
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.xtream.XtreamSeriesClient
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SeriesCategory(
    val categoryId: String,
    val providerCategoryKey: String,
    val name: String,
)

data class SeriesSummary(
    val seriesId: String,
    val providerSeriesId: Int,
    val categoryKey: String?,
    val name: String,
    val posterUrl: String?,
    val description: String?,
    val rating: Double?,
    val lastModifiedEpochSeconds: Long?,
    val isFavorite: Boolean,
)

data class SeriesEpisode(
    val episodeId: String,
    val seriesId: String,
    val seriesTitle: String,
    val providerEpisodeId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String?,
    val durationSeconds: Long?,
    val posterUrl: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val progressCompleted: Boolean,
    val progressUpdatedAtEpochMillis: Long?,
) {
    val resumeAvailable: Boolean
        get() = (positionMs ?: 0L) > 0L && !progressCompleted
}

data class SeriesSeason(
    val seasonId: String,
    val seasonNumber: Int,
    val name: String?,
    val airDate: String?,
    val posterUrl: String?,
    val episodes: List<SeriesEpisode>,
)

data class SeriesCatalog(
    val categories: List<SeriesCategory> = emptyList(),
    val series: List<SeriesSummary> = emptyList(),
    val continueWatching: List<SeriesEpisode> = emptyList(),
)

data class SeriesDetails(
    val series: SeriesSummary,
    val description: String?,
    val posterUrl: String?,
    val backdropUrls: List<String>,
    val releaseDate: String?,
    val genre: String?,
    val country: String?,
    val director: String?,
    val cast: String?,
    val rating: Double?,
    val seasons: List<SeriesSeason>,
)

class SeriesRepository(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
    private val xtreamSeriesClient: XtreamSeriesClient,
) {
    private val dao = database.seriesCatalogDao()

    fun observeCatalog(sourceId: String): Flow<SeriesCatalog> = combine(
        dao.observeCategories(sourceId),
        dao.observeSeries(sourceId),
        dao.observeContinueWatching(sourceId),
    ) { categoryRows, seriesRows, continueRows ->
        SeriesCatalog(
            categories = categoryRows.map { row ->
                SeriesCategory(
                    categoryId = row.categoryId,
                    providerCategoryKey = row.providerCategoryKey,
                    name = row.name,
                )
            },
            series = seriesRows.map(::mapSeries),
            continueWatching = continueRows.map(::mapEpisode),
        )
    }

    suspend fun refresh(sourceId: String): SourceResult<Int> {
        val access = when (val loaded = loadXtreamAccess(sourceId)) {
            is SourceResult.Success -> loaded.value
            is SourceResult.Failure -> return loaded
        }
        val categories = when (
            val result = xtreamSeriesClient.getSeriesCategories(
                serverUrl = access.serverUrl,
                credentials = access.credentials,
                allowCleartext = access.allowCleartext,
            )
        ) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        val series = when (
            val result = xtreamSeriesClient.getSeries(
                serverUrl = access.serverUrl,
                credentials = access.credentials,
                allowCleartext = access.allowCleartext,
            )
        ) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        val generation = CatalogRefreshGeneration.next()
        val categoryEntities = categories.mapIndexed { index, category ->
            ProviderSeriesCategoryEntity(
                categoryId = stableCategoryId(sourceId, category.id),
                sourceId = sourceId,
                providerCategoryKey = category.id,
                name = category.name.trim().ifBlank { "Untitled" },
                parentProviderKey = category.parentId,
                providerOrder = index.toLong(),
                lastSeenGeneration = generation,
            )
        }
        val seriesEntities = series.mapIndexed { index, item ->
            ProviderSeriesEntity(
                seriesId = stableSeriesId(sourceId, item.seriesId),
                sourceId = sourceId,
                providerSeriesId = item.seriesId.toString(),
                providerCategoryKey = item.categoryId,
                providerName = item.name.trim().ifBlank { "Untitled series" },
                posterRef = item.posterUrl,
                description = item.description,
                providerRating = item.rating,
                lastModifiedEpochSeconds = item.lastModifiedEpochSeconds,
                providerOrder = index.toLong(),
                lastSeenGeneration = generation,
            )
        }
        return try {
            dao.reconcileCatalog(
                sourceId = sourceId,
                generation = generation,
                categories = categoryEntities,
                series = seriesEntities,
            )
            SourceResult.Success(seriesEntities.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceResult.Failure(SourceError.Unknown)
        }
    }

    suspend fun cachedDetails(sourceId: String, seriesId: String): SeriesDetails? = try {
        val seriesEntity = dao.series(sourceId, seriesId) ?: return null
        buildStoredSeriesDetails(
            seriesEntity = seriesEntity,
            seasons = dao.seasons(seriesId),
            episodeRows = dao.episodeRows(sourceId, seriesId),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun details(sourceId: String, seriesId: String): SourceResult<SeriesDetails> {
        val seriesEntity = try {
            dao.series(sourceId, seriesId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceError.Unknown)
        } ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val providerSeriesId = seriesEntity.providerSeriesId.toIntOrNull()
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val access = when (val loaded = loadXtreamAccess(sourceId)) {
            is SourceResult.Success -> loaded.value
            is SourceResult.Failure -> return loaded
        }
        val info = when (
            val result = xtreamSeriesClient.getSeriesInfo(
                serverUrl = access.serverUrl,
                credentials = access.credentials,
                seriesId = providerSeriesId,
                allowCleartext = access.allowCleartext,
            )
        ) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        val seasonInfoByNumber = info.seasons.associateBy { it.seasonNumber }
        val seasonNumbers = (info.seasons.map { it.seasonNumber } + info.episodes.map { it.seasonNumber })
            .distinct()
            .sorted()
        val seasonEntities = seasonNumbers.map { seasonNumber ->
            val metadata = seasonInfoByNumber[seasonNumber]
            ProviderSeriesSeasonEntity(
                seasonId = stableSeasonId(seriesId, seasonNumber),
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                name = metadata?.name,
                airDate = metadata?.airDate,
                posterRef = metadata?.posterUrl ?: info.posterUrl ?: seriesEntity.posterRef,
            )
        }
        val episodeEntities = info.episodes.map { episode ->
            ProviderSeriesEpisodeEntity(
                episodeId = stableEpisodeId(seriesId, episode.episodeId),
                seriesId = seriesId,
                seasonId = stableSeasonId(seriesId, episode.seasonNumber),
                providerEpisodeId = episode.episodeId.toString(),
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                title = episode.title.trim().ifBlank { "Episode ${episode.episodeNumber}" },
                containerExtension = episode.containerExtension,
                durationSeconds = episode.durationSeconds,
                description = episode.description,
                posterRef = episode.posterUrl ?: info.posterUrl ?: seriesEntity.posterRef,
                providerRating = episode.rating,
                addedAtEpochSeconds = episode.addedAtEpochSeconds,
            )
        }
        try {
            dao.replaceDetails(
                seriesId = seriesId,
                seasons = seasonEntities,
                episodes = episodeEntities,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceError.Unknown)
        }
        val episodeRows = try {
            dao.episodeRows(sourceId, seriesId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceError.Unknown)
        }
        val episodesBySeason = episodeRows.map(::mapEpisode).groupBy(SeriesEpisode::seasonNumber)
        val summary = SeriesSummary(
            seriesId = seriesEntity.seriesId,
            providerSeriesId = providerSeriesId,
            categoryKey = seriesEntity.providerCategoryKey,
            name = info.name?.trim()?.takeIf(String::isNotBlank) ?: seriesEntity.providerName,
            posterUrl = info.posterUrl ?: seriesEntity.posterRef,
            description = info.description ?: seriesEntity.description,
            rating = info.rating ?: seriesEntity.providerRating,
            lastModifiedEpochSeconds = seriesEntity.lastModifiedEpochSeconds,
            isFavorite = false,
        )
        return SourceResult.Success(
            SeriesDetails(
                series = summary,
                description = info.description ?: seriesEntity.description,
                posterUrl = info.posterUrl ?: seriesEntity.posterRef,
                backdropUrls = info.backdropUrls,
                releaseDate = info.releaseDate,
                genre = info.genre,
                country = info.country,
                director = info.director,
                cast = info.cast,
                rating = info.rating ?: seriesEntity.providerRating,
                seasons = seasonEntities.map { season ->
                    SeriesSeason(
                        seasonId = season.seasonId,
                        seasonNumber = season.seasonNumber,
                        name = season.name,
                        airDate = season.airDate,
                        posterUrl = season.posterRef,
                        episodes = episodesBySeason[season.seasonNumber].orEmpty(),
                    )
                },
            ),
        )
    }

    suspend fun setFavorite(
        sourceId: String,
        seriesId: String,
        favorite: Boolean,
    ): Boolean = try {
        if (favorite) {
            dao.upsertFavorite(
                MediaFavoriteEntity(
                    sourceId = sourceId,
                    mediaKind = SeriesMediaKinds.SERIES,
                    contentId = seriesId,
                    addedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.deleteFavorite(sourceId, SeriesMediaKinds.SERIES, seriesId)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun saveEpisodeProgress(
        sourceId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean = try {
        val existing = dao.progress(
            sourceId = sourceId,
            mediaKind = SeriesMediaKinds.EPISODE,
            contentId = episodeId,
        )
        val positionForSave = PlaybackProgressPolicy.positionForSave(
            positionMs = positionMs,
            fallbackPositionMs = existing?.positionMs,
        )
        val normalized = PlaybackProgressPolicy.normalize(
            positionMs = positionForSave,
            durationMs = durationMs,
            fallbackDurationMs = existing?.durationMs,
        )
        dao.upsertProgress(
            PlaybackProgressEntity(
                sourceId = sourceId,
                mediaKind = SeriesMediaKinds.EPISODE,
                contentId = episodeId,
                positionMs = normalized.positionMs,
                durationMs = normalized.durationMs,
                completed = normalized.completed,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun clearEpisodeProgress(sourceId: String, episodeId: String): Boolean = try {
        dao.clearProgress(sourceId, SeriesMediaKinds.EPISODE, episodeId)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun loadXtreamAccess(sourceId: String): SourceResult<XtreamAccess> {
        val source = try {
            database.playlistSourceDao().getById(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceError.Unknown)
        } ?: return SourceResult.Failure(SourceError.Unknown)
        if (source.sourceKind != SourceKinds.XTREAM) {
            return SourceResult.Failure(SourceError.Unknown)
        }
        val locator = try {
            sensitiveValueStore.get(SensitiveValueRef(source.locatorRef))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }?.let(XtreamSourceLocatorCodec::parse)
            ?: return SourceResult.Failure(SourceError.CredentialUnavailable)
        val credentialRef = source.credentialRef
            ?: return SourceResult.Failure(SourceError.CredentialUnavailable)
        val credentials = try {
            credentialStore.get(CredentialRef(credentialRef))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return SourceResult.Failure(SourceError.CredentialUnavailable)
        return SourceResult.Success(
            XtreamAccess(
                serverUrl = locator.serverUrl,
                allowCleartext = locator.allowCleartext,
                credentials = credentials,
            ),
        )
    }

    private fun mapSeries(row: SeriesRow): SeriesSummary = SeriesSummary(
        seriesId = row.seriesId,
        providerSeriesId = row.providerSeriesId.toIntOrNull() ?: -1,
        categoryKey = row.providerCategoryKey,
        name = row.providerName,
        posterUrl = row.posterRef,
        description = row.description,
        rating = row.providerRating,
        lastModifiedEpochSeconds = row.lastModifiedEpochSeconds,
        isFavorite = row.isFavorite,
    )

    private fun mapEpisode(row: EpisodeProgressRow): SeriesEpisode = SeriesEpisode(
        episodeId = row.episodeId,
        seriesId = row.seriesId,
        seriesTitle = row.seriesTitle,
        providerEpisodeId = row.providerEpisodeId.toIntOrNull() ?: -1,
        seasonNumber = row.seasonNumber,
        episodeNumber = row.episodeNumber,
        title = row.title,
        containerExtension = row.containerExtension,
        durationSeconds = row.durationSeconds,
        posterUrl = row.posterRef,
        positionMs = row.positionMs,
        durationMs = row.durationMs,
        progressCompleted = row.progressCompleted == true,
        progressUpdatedAtEpochMillis = row.progressUpdatedAtEpochMillis,
    )

    private data class XtreamAccess(
        val serverUrl: String,
        val allowCleartext: Boolean,
        val credentials: XtreamCredentials,
    )

    companion object {
        fun stableCategoryId(sourceId: String, providerCategoryKey: String): String =
            "$sourceId:series-category:$providerCategoryKey"

        fun stableSeriesId(sourceId: String, providerSeriesId: Int): String =
            "$sourceId:series:$providerSeriesId"

        fun stableSeasonId(seriesId: String, seasonNumber: Int): String =
            "$seriesId:season:$seasonNumber"

        fun stableEpisodeId(seriesId: String, providerEpisodeId: Int): String =
            "$seriesId:episode:$providerEpisodeId"
    }
}
