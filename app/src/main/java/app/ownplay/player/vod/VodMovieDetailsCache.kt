package app.ownplay.player.vod

import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.shouldRefreshSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal class VodMovieDetailsCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(
        val sourceId: String,
        val movieId: String,
    )

    private data class CacheEntry(
        val details: VodMovieDetails,
        val lastSuccessAtEpochMillis: Long,
    )

    private val lock = Any()
    private val cache = mutableMapOf<CacheKey, CacheEntry>()
    private val inFlight = mutableMapOf<CacheKey, Deferred<SourceResult<VodMovieDetails>>>()

    fun peek(sourceId: String, movieId: String): VodMovieDetails? =
        synchronized(lock) {
            cache[CacheKey(sourceId = sourceId, movieId = movieId)]?.details
        }

    suspend fun refreshIfStale(
        sourceId: String,
        movieId: String,
        refresh: suspend () -> SourceResult<VodMovieDetails>,
    ): SourceResult<VodMovieDetails> {
        val key = CacheKey(sourceId = sourceId, movieId = movieId)
        val nowEpochMillis = clock()
        var freshDetails: VodMovieDetails? = null
        val deferred = synchronized(lock) {
            val cached = cache[key]
            if (
                cached != null &&
                !shouldRefreshSource(
                    lastSuccessAtEpochMillis = cached.lastSuccessAtEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                )
            ) {
                freshDetails = cached.details
                null
            } else {
                inFlight[key] ?: createDeferred(key, refresh).also { created ->
                    inFlight[key] = created
                    created.start()
                }
            }
        }
        freshDetails?.let { details ->
            return SourceResult.Success(details)
        }
        return deferred!!.await()
    }

    private fun createDeferred(
        key: CacheKey,
        refresh: suspend () -> SourceResult<VodMovieDetails>,
    ): Deferred<SourceResult<VodMovieDetails>> {
        lateinit var deferred: Deferred<SourceResult<VodMovieDetails>>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                when (val result = refresh()) {
                    is SourceResult.Success -> {
                        synchronized(lock) {
                            cache[key] = CacheEntry(
                                details = result.value,
                                lastSuccessAtEpochMillis = clock(),
                            )
                        }
                        result
                    }
                    is SourceResult.Failure -> result
                }
            } finally {
                synchronized(lock) {
                    if (inFlight[key] === deferred) {
                        inFlight.remove(key)
                    }
                }
            }
        }
        return deferred
    }

    companion object {
        val processShared = VodMovieDetailsCache()
    }
}
