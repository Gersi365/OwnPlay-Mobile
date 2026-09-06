package app.ownplay.player.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal class MobileChannelLogoRequestCoordinator<T>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val inFlight = mutableMapOf<String, Deferred<T>>()

    suspend fun coalesce(
        url: String,
        request: suspend () -> T,
    ): T {
        val deferred = synchronized(lock) {
            inFlight[url] ?: createDeferred(url, request).also { created ->
                inFlight[url] = created
                created.start()
            }
        }
        return deferred.await()
    }

    private fun createDeferred(
        url: String,
        request: suspend () -> T,
    ): Deferred<T> {
        lateinit var deferred: Deferred<T>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                request()
            } finally {
                synchronized(lock) {
                    if (inFlight[url] === deferred) {
                        inFlight.remove(url)
                    }
                }
            }
        }
        return deferred
    }
}
