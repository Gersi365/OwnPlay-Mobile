package app.ownplay.player.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal class OnDemandCatalogRefreshCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class RefreshKey(
        val sourceId: String,
        val kind: OnDemandCatalogKind,
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<RefreshKey, Deferred<SourceResult<Int>>>()

    suspend fun coalesce(
        sourceId: String,
        kind: OnDemandCatalogKind,
        refresh: suspend () -> SourceResult<Int>,
    ): SourceResult<Int> {
        val key = RefreshKey(sourceId = sourceId, kind = kind)
        val deferred = synchronized(lock) {
            inFlight[key] ?: createDeferred(key, refresh).also { created ->
                inFlight[key] = created
                created.start()
            }
        }
        return deferred.await()
    }

    private fun createDeferred(
        key: RefreshKey,
        refresh: suspend () -> SourceResult<Int>,
    ): Deferred<SourceResult<Int>> {
        lateinit var deferred: Deferred<SourceResult<Int>>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                refresh()
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
        val processShared = OnDemandCatalogRefreshCoordinator()
    }
}
