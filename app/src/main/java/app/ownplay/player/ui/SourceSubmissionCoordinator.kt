package app.ownplay.player.ui

import app.ownplay.player.OwnPlayAppRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns source-submission work independently from the Add Playlist dialog lifecycle.
 *
 * The dialog can close immediately after local validation while the network/catalog
 * import continues. Submissions are serialized so the runtime's single visible sync
 * state remains deterministic.
 */
internal object SourceSubmissionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submissionMutex = Mutex()

    fun submitXtream(
        runtime: OwnPlayAppRuntime,
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        allowCleartext: Boolean,
    ) {
        scope.launch {
            submissionMutex.withLock {
                runSubmission {
                    runtime.addXtreamSource(
                        name = name,
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        allowCleartext = allowCleartext,
                    )
                }
            }
        }
    }

    fun submitRemoteM3u(
        runtime: OwnPlayAppRuntime,
        name: String,
        playlistUrl: String,
        allowCleartext: Boolean,
    ) {
        scope.launch {
            submissionMutex.withLock {
                runSubmission {
                    runtime.addRemoteM3uSource(
                        name = name,
                        playlistUrl = playlistUrl,
                        allowCleartext = allowCleartext,
                    )
                }
            }
        }
    }

    fun submitLocalM3u(
        runtime: OwnPlayAppRuntime,
        name: String,
        documentUri: String,
        allowCleartext: Boolean,
    ) {
        scope.launch {
            submissionMutex.withLock {
                runSubmission {
                    runtime.addLocalM3uSource(
                        name = name,
                        documentUri = documentUri,
                        allowCleartext = allowCleartext,
                    )
                }
            }
        }
    }

    private suspend fun runSubmission(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Source runtime methods convert expected failures into SourceSyncState.
            // This guard only prevents an unexpected background exception from
            // crashing the process.
        }
    }
}
