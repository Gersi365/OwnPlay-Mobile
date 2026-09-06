package app.ownplay.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PendingImportExecutionState(
    val queuedSourceIds: Set<String> = emptySet(),
    val activeSourceIds: Set<String> = emptySet(),
)

/**
 * Process-local execution snapshot used by Settings to distinguish a source that is waiting for an
 * import slot from one that is actually performing network/catalog work.
 */
internal object PendingImportExecutionTracker {
    private val _state = MutableStateFlow(PendingImportExecutionState())
    val state: StateFlow<PendingImportExecutionState> = _state.asStateFlow()

    fun markQueued(sourceId: String) {
        _state.update { current ->
            current.copy(
                queuedSourceIds = current.queuedSourceIds + sourceId,
                activeSourceIds = current.activeSourceIds - sourceId,
            )
        }
    }

    fun markActive(sourceId: String) {
        _state.update { current ->
            current.copy(
                queuedSourceIds = current.queuedSourceIds - sourceId,
                activeSourceIds = current.activeSourceIds + sourceId,
            )
        }
    }

    fun clear(sourceId: String) {
        _state.update { current ->
            current.copy(
                queuedSourceIds = current.queuedSourceIds - sourceId,
                activeSourceIds = current.activeSourceIds - sourceId,
            )
        }
    }
}

/**
 * Schedules recoverable playlist imports without allowing one slow provider to block every source.
 * Duplicate schedules for the same source are collapsed into the existing job.
 */
internal class PendingImportCoordinator(
    private val scope: CoroutineScope,
    maxConcurrentImports: Int,
    private val importSource: suspend (String) -> Unit,
) {
    private val gate = Semaphore(maxConcurrentImports.also { require(it > 0) })
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun schedule(sourceId: String) {
        val normalizedId = sourceId.trim()
        if (normalizedId.isEmpty()) return

        var jobToStart: Job? = null
        synchronized(lock) {
            // A LAZY coroutine is not active until start(), so checking isActive here leaves a
            // race where two concurrent schedule() calls can create duplicate imports. Presence in
            // the map is the reservation; completion/cancel removes that reservation.
            if (jobs.containsKey(normalizedId)) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val thisJob = coroutineContext.job
                try {
                    gate.withPermit {
                        PendingImportExecutionTracker.markActive(normalizedId)
                        importSource(normalizedId)
                    }
                } finally {
                    synchronized(lock) {
                        // A cancelled job may already have been replaced by a new schedule for the
                        // same source. Only the job that still owns the reservation may clear the
                        // execution snapshot; otherwise the old cleanup would erase the new job's
                        // queued/active state.
                        if (jobs[normalizedId] === thisJob) {
                            jobs.remove(normalizedId)
                            PendingImportExecutionTracker.clear(normalizedId)
                        }
                    }
                }
            }
            jobs[normalizedId] = job
            PendingImportExecutionTracker.markQueued(normalizedId)
            jobToStart = job
        }
        jobToStart?.start()
    }

    fun cancel(sourceId: String) {
        val normalizedId = sourceId.trim()
        if (normalizedId.isEmpty()) return
        val job = synchronized(lock) {
            val removed = jobs.remove(normalizedId)
            // Clear while holding the same reservation lock used by schedule(). This prevents a
            // replacement schedule from being marked queued and then erased by a late cancel clear.
            PendingImportExecutionTracker.clear(normalizedId)
            removed
        }
        job?.cancel()
    }

    fun isActive(sourceId: String): Boolean = synchronized(lock) {
        jobs[sourceId.trim()]?.isActive == true
    }
}
