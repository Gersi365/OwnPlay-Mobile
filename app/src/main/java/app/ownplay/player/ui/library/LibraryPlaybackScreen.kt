package app.ownplay.player.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.ui.PlayerView
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.playback.PlaybackInteractionBridge
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.ui.OnDemandPlaybackSurface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class LibraryPlaybackSession(
    val download: OfflineDownload,
    val initialPositionMs: Long,
)

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LibraryPlaybackScreen(
    runtime: OwnPlayAppRuntime,
    session: LibraryPlaybackSession,
    onExit: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long?) -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
    backContentDescription: String = "Back to Library",
    contextLabel: String = "Library",
) {
    val playbackState by runtime.playbackController.state.collectAsState()
    val backOwner = remember(session.download.downloadId) { Any() }
    var playerView by remember(session.download.downloadId) { mutableStateOf<PlayerView?>(null) }
    var currentPosition by remember(session.download.downloadId) {
        mutableStateOf(session.initialPositionMs)
    }
    var duration by remember(session.download.downloadId) { mutableStateOf(0L) }
    var resumeApplied by remember(session.download.downloadId) { mutableStateOf(false) }

    BackHandler(onBack = onExit)

    DisposableEffect(session.download.downloadId, backOwner) {
        onFullscreenStateChanged(true)
        PlaybackInteractionBridge.registerBackAction(backOwner, onExit)
        onDispose {
            if (currentPosition > 0L) {
                onProgress(currentPosition, duration.takeIf { it > 0L })
            }
            // Composition disposal also happens during Activity recreation. Explicit navigation
            // owns playback teardown via onExit; disposal only detaches UI/back ownership so the
            // process-scoped Offline presentation can reattach to the running session.
            PlaybackInteractionBridge.clearBackAction(backOwner)
            onFullscreenStateChanged(false)
        }
    }

    LaunchedEffect(playbackState, playerView, session.download.downloadId) {
        val request = when (val state = playbackState) {
            is PlaybackState.Playing -> state.request
            is PlaybackState.Paused -> state.request
            else -> null
        }
        if (
            !resumeApplied &&
            request != null &&
            request.sourceId == session.download.sourceId &&
            request.channelId == session.download.contentId
        ) {
            val player = playerView?.player ?: return@LaunchedEffect
            if (player.currentPosition <= 5_000L) {
                session.initialPositionMs.takeIf { it > 5_000L }?.let { position ->
                    player.seekTo(position)
                    currentPosition = position
                }
            }
            duration = player.duration.takeIf { it > 0L } ?: duration
            resumeApplied = true
        }
    }

    LaunchedEffect(playerView, session.download.downloadId) {
        var saveTick = 0
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            val player = playerView?.player ?: continue
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: duration
            saveTick += 1
            if (saveTick >= 5 && currentPosition > 0L) {
                saveTick = 0
                onProgress(currentPosition, duration.takeIf { it > 0L })
            }
        }
    }

    OnDemandPlaybackSurface(
        runtime = runtime,
        contentKey = session.download.downloadId,
        title = session.download.title,
        playbackState = playbackState,
        currentPositionMs = currentPosition,
        durationMs = duration,
        exitRequested = false,
        onExit = onExit,
        onPlayerViewAvailable = { view -> playerView = view },
        onPlayerViewReleased = { view ->
            if (playerView === view) playerView = null
        },
        onSeekPositionChanged = { position -> currentPosition = position },
    )
}
