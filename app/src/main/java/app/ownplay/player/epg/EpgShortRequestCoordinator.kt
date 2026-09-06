package app.ownplay.player.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal class EpgShortRequestCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class RequestKey(
        val sourceId: String,
        val channelId: String,
        val generation: Long,
    )

    private val lock = Any()
    private val inFlight = mutableMapOf<RequestKey, Deferred<List<EpgProgram>>>()

    suspend fun coalesce(
        sourceId: String,
        channelId: String,
        generation: Long,
        load: suspend () -> List<EpgProgram>,
    ): List<EpgProgram> {
        val key = RequestKey(
            sourceId = sourceId,
            channelId = channelId,
            generation = generation,
        )
        val deferred = synchronized(lock) {
            inFlight[key] ?: createDeferred(key, load).also { created ->
                inFlight[key] = created
                created.start()
            }
        }
        return deferred.await()
    }

    private fun createDeferred(
        key: RequestKey,
        load: suspend () -> List<EpgProgram>,
    ): Deferred<List<EpgProgram>> {
        lateinit var deferred: Deferred<List<EpgProgram>>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                load()
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
}
