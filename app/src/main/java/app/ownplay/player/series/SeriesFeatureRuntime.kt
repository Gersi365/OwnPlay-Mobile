package app.ownplay.player.series

import android.content.Context
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.secure.AndroidKeystoreSensitiveValueStore
import app.ownplay.player.source.OnDemandCatalogKind
import app.ownplay.player.source.OnDemandCatalogRefreshCoordinator
import app.ownplay.player.source.OnDemandCatalogRefreshInvocationGate
import app.ownplay.player.source.OnDemandCatalogRefreshMode
import app.ownplay.player.source.OnDemandCatalogRefreshStore
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.AndroidKeystoreCredentialStore
import app.ownplay.player.source.shouldRefreshOnDemandCatalog
import app.ownplay.player.source.xtream.XtreamSeriesClient
import kotlinx.coroutines.flow.Flow

class SeriesFeatureRuntime(
    context: Context,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val database = sharedDatabase(applicationContext)
    private val repository = SeriesRepository(
        database = database,
        sensitiveValueStore = AndroidKeystoreSensitiveValueStore(applicationContext),
        credentialStore = AndroidKeystoreCredentialStore(applicationContext),
        xtreamSeriesClient = XtreamSeriesClient(),
    )
    private val refreshStore = OnDemandCatalogRefreshStore(applicationContext)
    private val refreshInvocationGate = OnDemandCatalogRefreshInvocationGate()

    fun observeCatalog(sourceId: String): Flow<SeriesCatalog> = repository.observeCatalog(sourceId)

    suspend fun refresh(sourceId: String): SourceResult<Int> {
        val mode = refreshInvocationGate.nextMode(sourceId)
        if (mode == OnDemandCatalogRefreshMode.AUTOMATIC) {
            val lastSuccessAtEpochMillis = refreshStore.lastSuccessAtEpochMillis(
                sourceId = sourceId,
                kind = OnDemandCatalogKind.SERIES,
            )
            if (
                !shouldRefreshOnDemandCatalog(
                    mode = mode,
                    lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
                    nowEpochMillis = System.currentTimeMillis(),
                )
            ) {
                return SourceResult.Success(0)
            }
        }

        return OnDemandCatalogRefreshCoordinator.processShared.coalesce(
            sourceId = sourceId,
            kind = OnDemandCatalogKind.SERIES,
        ) {
            val result = repository.refresh(sourceId)
            if (result is SourceResult.Success<*>) {
                refreshStore.markSuccess(
                    sourceId = sourceId,
                    kind = OnDemandCatalogKind.SERIES,
                    successAtEpochMillis = System.currentTimeMillis(),
                )
            }
            result
        }
    }

    suspend fun cachedDetails(sourceId: String, seriesId: String): SeriesDetails? =
        SeriesDetailsCache.processShared.peek(sourceId, seriesId)
            ?: repository.cachedDetails(sourceId, seriesId)

    suspend fun details(sourceId: String, seriesId: String): SourceResult<SeriesDetails> =
        SeriesDetailsCache.processShared.refreshIfStale(
            sourceId = sourceId,
            seriesId = seriesId,
        ) {
            repository.details(sourceId, seriesId)
        }

    suspend fun setFavorite(sourceId: String, seriesId: String, favorite: Boolean): Boolean =
        repository.setFavorite(sourceId, seriesId, favorite)

    suspend fun saveEpisodeProgress(
        sourceId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean = repository.saveEpisodeProgress(sourceId, episodeId, positionMs, durationMs)

    suspend fun clearEpisodeProgress(sourceId: String, episodeId: String): Boolean =
        repository.clearEpisodeProgress(sourceId, episodeId)

    override fun close() {
        // Series routes are screen-scoped, but their Room database must outlive route disposal.
        // Animated section transitions and active WorkManager episode-download writes can overlap
        // the outgoing Series composition, so the shared instance intentionally lives for the process.
    }

    private companion object {
        @Volatile
        private var processDatabase: OwnPlayDatabase? = null

        fun sharedDatabase(context: Context): OwnPlayDatabase =
            processDatabase ?: synchronized(this) {
                processDatabase ?: OwnPlayDatabase.create(context).also { database ->
                    processDatabase = database
                }
            }
    }
}
