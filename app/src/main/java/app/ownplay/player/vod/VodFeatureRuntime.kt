package app.ownplay.player.vod

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
import app.ownplay.player.source.xtream.XtreamClient
import kotlinx.coroutines.flow.Flow

class VodFeatureRuntime(
    context: Context,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val database = sharedDatabase(applicationContext)
    private val repository = VodRepository(
        database = database,
        sensitiveValueStore = AndroidKeystoreSensitiveValueStore(applicationContext),
        credentialStore = AndroidKeystoreCredentialStore(applicationContext),
        xtreamClient = XtreamClient(),
    )
    private val refreshStore = OnDemandCatalogRefreshStore(applicationContext)
    private val refreshInvocationGate = OnDemandCatalogRefreshInvocationGate()

    fun observeCatalog(sourceId: String): Flow<VodCatalog> = repository.observeCatalog(sourceId)

    suspend fun refresh(sourceId: String): SourceResult<Int> {
        val mode = refreshInvocationGate.nextMode(sourceId)
        if (mode == OnDemandCatalogRefreshMode.AUTOMATIC) {
            val lastSuccessAtEpochMillis = refreshStore.lastSuccessAtEpochMillis(
                sourceId = sourceId,
                kind = OnDemandCatalogKind.VOD,
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
            kind = OnDemandCatalogKind.VOD,
        ) {
            val result = repository.refresh(sourceId)
            if (result is SourceResult.Success<*>) {
                refreshStore.markSuccess(
                    sourceId = sourceId,
                    kind = OnDemandCatalogKind.VOD,
                    successAtEpochMillis = System.currentTimeMillis(),
                )
            }
            result
        }
    }

    suspend fun details(sourceId: String, movieId: String): SourceResult<VodMovieDetails> =
        VodMovieDetailsCache.processShared.refreshIfStale(
            sourceId = sourceId,
            movieId = movieId,
        ) {
            repository.details(sourceId, movieId)
        }

    suspend fun setFavorite(sourceId: String, movieId: String, favorite: Boolean): Boolean =
        repository.setFavorite(sourceId, movieId, favorite)

    suspend fun saveProgress(
        sourceId: String,
        movieId: String,
        positionMs: Long,
        durationMs: Long?,
    ): Boolean = repository.saveProgress(sourceId, movieId, positionMs, durationMs)

    suspend fun clearProgress(sourceId: String, movieId: String): Boolean =
        repository.clearProgress(sourceId, movieId)

    override fun close() {
        // VOD routes are screen-scoped, but their Room database must outlive route disposal.
        // Animated section transitions and active WorkManager download writes can overlap the
        // outgoing VOD composition, so closing this database from onDispose creates an unsafe
        // lifecycle boundary. The shared instance intentionally lives for the process.
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
