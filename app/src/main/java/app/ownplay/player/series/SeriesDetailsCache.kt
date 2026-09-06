package app.ownplay.player.series

import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.shouldRefreshSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal class SeriesDetailsCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(
        val sourceId: String,
        val seriesId: String,
    )

    private data class CacheEntry(
        val details: SeriesDetails,
        val lastSuccessAtEpochMillis: Long,
    )

    private val lock = Any()
    private val cache = mutableMapOf<CacheKey, CacheEntry>()
    private val inFlight = mutableMapOf<CacheKey, Deferred<SourceResult<SeriesDetails>>>()

    fun peek(sourceId: String, seriesId: String): SeriesDetails? =
        synchronized(lock) {
            cache[CacheKey(sourceId = sourceId, seriesId = seriesId)]?.details
        }

    suspend fun refreshIfStale(
        sourceId: String,
        seriesId: String,
        refresh: suspend () -> SourceResult<SeriesDetails>,
    ): SourceResult<SeriesDetails> {
        val key = CacheKey(sourceId = sourceId, seriesId = seriesId)
        val nowEpochMillis = clock()
        var freshDetails: SeriesDetails? = null
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
        refresh: suspend () -> SourceResult<SeriesDetails>,
    ): Deferred<SourceResult<SeriesDetails>> {
        lateinit var deferred: Deferred<SourceResult<SeriesDetails>>
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
        val processShared = SeriesDetailsCache()
    }
}
