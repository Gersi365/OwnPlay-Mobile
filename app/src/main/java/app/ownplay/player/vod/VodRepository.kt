package app.ownplay.player.vod

import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.SourceKinds
import app.ownplay.player.persistence.reconcile.CatalogRefreshGeneration
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import app.ownplay.player.persistence.vod.MediaFavoriteEntity
import app.ownplay.player.persistence.vod.MediaKinds
import app.ownplay.player.persistence.vod.PlaybackProgressEntity
import app.ownplay.player.persistence.vod.ProviderMovieEntity
import app.ownplay.player.persistence.vod.ProviderVodCategoryEntity
import app.ownplay.player.persistence.vod.VodMovieRow
import app.ownplay.player.playback.MediaDuration
import app.ownplay.player.playback.PlaybackProgressPolicy
import app.ownplay.player.source.CredentialRef
import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.CredentialStore
import app.ownplay.player.source.credential.XtreamCredentials
import app.ownplay.player.source.xtream.XtreamClient
import app.ownplay.player.source.xtream.XtreamSourceLocatorCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class VodCategory(
    val categoryId: String,
    val providerCategoryKey: String,
    val name: String,
)

data class VodMovie(
    val movieId: String,
    val providerStreamId: Int,
    val categoryKey: String?,
    val name: String,
    val posterUrl: String?,
    val containerExtension: String?,
    val rating: Double?,
    val addedAtEpochSeconds: Long?,
    val isFavorite: Boolean,
    val positionMs: Long?,
    val durationMs: Long?,
    val progressCompleted: Boolean,
    val progressUpdatedAtEpochMillis: Long?,
) {
    val resumeAvailable: Boolean
        get() = (positionMs ?: 0L) > 0L && !progressCompleted
}

data class VodCatalog(
    val categories: List<VodCategory> = emptyList(),
    val movies: List<VodMovie> = emptyList(),
    val continueWatching: List<VodMovie> = emptyList(),
)

data class VodMovieDetails(
    val movie: VodMovie,
    val originalName: String?,
    val description: String?,
    val posterUrl: String?,
    val backdropUrls: List<String>,
    val releaseDate: String?,
    val durationSeconds: Long?,
    val durationLabel: String?,
    val genre: String?,
    val country: String?,
    val director: String?,
    val cast: String?,
    val rating: Double?,
    val youtubeTrailer: String?,
)

class VodRepository(
    private val database: OwnPlayDatabase,
    private val sensitiveValueStore: SensitiveValueStore,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
) {
    private val dao = database.vodCatalogDao()

    fun observeCatalog(sourceId: String): Flow<VodCatalog> = combine(
        dao.observeCategories(sourceId),
        dao.observeMovies(sourceId),
        dao.observeContinueWatching(sourceId),
    ) { categoryRows, movieRows, continueRows ->
        VodCatalog(
            categories = categoryRows.map { row ->
                VodCategory(
                    categoryId = row.categoryId,
                    providerCategoryKey = row.providerCategoryKey,
                    name = row.name,
                )
            },
            movies = movieRows.map(::mapMovie),
            continueWatching = continueRows.map(::mapMovie),
        )
    }

    suspend fun refresh(sourceId: String): SourceResult<Int> {
        val access = when (val loaded = loadXtreamAccess(sourceId)) {
            is SourceResult.Success -> loaded.value
            is SourceResult.Failure -> return loaded
        }
        val categories = when (
            val result = xtreamClient.getVodCategories(
                serverUrl = access.serverUrl,
                credentials = access.credentials,
                allowCleartext = access.allowCleartext,
            )
        ) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        val movies = when (
            val result = xtreamClient.getVodStreams(
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
            ProviderVodCategoryEntity(
                categoryId = stableCategoryId(sourceId, category.id),
                sourceId = sourceId,
                providerCategoryKey = category.id,
                name = category.name.trim().ifBlank { "Untitled" },
                parentProviderKey = category.parentId,
                providerOrder = index.toLong(),
                lastSeenGeneration = generation,
            )
        }
        val movieEntities = movies.mapIndexed { index, movie ->
            ProviderMovieEntity(
                movieId = stableMovieId(sourceId, movie.streamId),
                sourceId = sourceId,
                providerStreamId = movie.streamId.toString(),
                providerCategoryKey = movie.categoryId,
                providerName = movie.name.trim().ifBlank { "Untitled movie" },
                posterRef = movie.posterUrl,
                containerExtension = movie.containerExtension,
                providerRating = movie.rating,
                addedAtEpochSeconds = movie.addedAtEpochSeconds,
                providerOrder = index.toLong(),
                lastSeenGeneration = generation,
            )
        }

        return try {
            dao.reconcileCatalog(
                sourceId = sourceId,
                generation = generation,
                categories = categoryEntities,
                movies = movieEntities,
            )
            SourceResult.Success(movieEntities.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceResult.Failure(SourceError.Unknown)
        }
    }

    suspend fun details(sourceId: String, movieId: String): SourceResult<VodMovieDetails> {
        val entity = try {
            dao.movie(sourceId, movieId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceError.Unknown)
        } ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val streamId = entity.providerStreamId.toIntOrNull()
            ?: return SourceResult.Failure(SourceError.MalformedResponse)
        val access = when (val loaded = loadXtreamAccess(sourceId)) {
            is SourceResult.Success -> loaded.value
            is SourceResult.Failure -> return loaded
        }
        val info = when (
            val result = xtreamClient.getVodInfo(
                serverUrl = access.serverUrl,
                credentials = access.credentials,
                vodId = streamId,
                allowCleartext = access.allowCleartext,
            )
        ) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }

        val catalogMovie = VodMovie(
            movieId = entity.movieId,
            providerStreamId = streamId,
            categoryKey = entity.providerCategoryKey,
            name = entity.providerName,
            posterUrl = entity.posterRef,
            containerExtension = info.containerExtension ?: entity.containerExtension,
            rating = info.rating ?: entity.providerRating,
            addedAtEpochSeconds = entity.addedAtEpochSeconds,
            isFavorite = false,
            positionMs = null,
            durationMs = MediaDuration.secondsToMillis(info.durationSeconds),
            progressCompleted = false,
            progressUpdatedAtEpochMillis = null,
        )
        return SourceResult.Success(
            VodMovieDetails(
                movie = catalogMovie,
                originalName = info.originalName,
                description = info.description,
                posterUrl = info.posterUrl ?: entity.posterRef,
                backdropUrls = info.backdropUrls,
                releaseDate = info.releaseDate,
                durationSeconds = info.durationSeconds,
                durationLabel = info.durationLabel,
                genre = info.genre,
                country = info.country,
                director = info.director,
                cast = info.cast,
                rating = info.rating ?: entity.providerRating,
                youtubeTrailer = info.youtubeTrailer,
            ),
        )
    }

    suspend fun setFavorite(
        sourceId: String,
        movieId: String,
        favorite: Boolean,
    ): Boolean = try {
        if (favorite) {
            dao.upsertFavorite(
                MediaFavoriteEntity(
                    sourceId = sourceId,
                    mediaKind = MediaKinds.MOVIE,
                    contentId = movieId,
                    addedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.deleteFavorite(sourceId, MediaKinds.MOVIE, movieId)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    suspend fun saveProgress(
        sourceId: String,
        movieId: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean = try {
        val existing = dao.progress(
            sourceId = sourceId,
            mediaKind = MediaKinds.MOVIE,
            contentId = movieId,
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
                mediaKind = MediaKinds.MOVIE,
                contentId = movieId,
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

    suspend fun clearProgress(sourceId: String, movieId: String): Boolean = try {
        dao.clearProgress(sourceId, MediaKinds.MOVIE, movieId)
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

    private fun mapMovie(row: VodMovieRow): VodMovie = VodMovie(
        movieId = row.movieId,
        providerStreamId = row.providerStreamId.toIntOrNull() ?: -1,
        categoryKey = row.providerCategoryKey,
        name = row.providerName,
        posterUrl = row.posterRef,
        containerExtension = row.containerExtension,
        rating = row.providerRating,
        addedAtEpochSeconds = row.addedAtEpochSeconds,
        isFavorite = row.isFavorite,
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
            "$sourceId:vod-category:$providerCategoryKey"

        fun stableMovieId(sourceId: String, providerStreamId: Int): String =
            "$sourceId:movie:$providerStreamId"
    }
}
