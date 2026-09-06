from pathlib import Path

VOD_PATH = Path("app/src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
TEST_PATH = Path("app/src/test/java/app/ownplay/player/ui/OnDemandPlaybackPresentationContractTest.kt")

vod = VOD_PATH.read_text()
start_marker = "@OptIn(UnstableApi::class)\n@Composable\nprivate fun VodPlaybackScreen("
end_marker = "\n@Composable\nprivate fun VodUnavailableState("
start = vod.index(start_marker)
end = vod.index(end_marker, start)

replacement = '''@OptIn(UnstableApi::class)
@Composable
private fun VodPlaybackScreen(
    runtime: OwnPlayAppRuntime,
    featureRuntime: VodFeatureRuntime,
    sourceId: String,
    movie: VodMovie,
    onExit: () -> Unit,
    onFullscreenStateChanged: (Boolean) -> Unit,
) {
    val playbackState by runtime.playbackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val backOwner = remember(movie.movieId) { Any() }
    var playerView by remember(movie.movieId) { mutableStateOf<PlayerView?>(null) }
    var currentPosition by remember(movie.movieId) { mutableStateOf(movie.positionMs ?: 0L) }
    var duration by remember(movie.movieId) { mutableStateOf(movie.durationMs ?: 0L) }
    var resumeApplied by remember(movie.movieId) { mutableStateOf(false) }
    var exitRequested by remember(movie.movieId) { mutableStateOf(false) }

    fun exitPlayback() {
        if (exitRequested) return
        exitRequested = true
        val lastPosition = currentPosition
        val lastDuration = duration.takeIf { it > 0L }
        scope.launch {
            withTimeoutOrNull(VOD_EXIT_PROGRESS_SAVE_TIMEOUT_MILLIS) {
                featureRuntime.saveProgress(sourceId, movie.movieId, lastPosition, lastDuration)
            }
            runtime.playbackController.stopIfCurrent(
                sourceId = sourceId,
                channelId = movie.movieId,
                mediaKind = PlaybackMediaKind.MOVIE,
            )
            onFullscreenStateChanged(false)
            onExit()
        }
    }

    DisposableEffect(movie.movieId, backOwner) {
        onFullscreenStateChanged(true)
        PlaybackInteractionBridge.registerBackAction(backOwner, ::exitPlayback)
        onDispose {
            PlaybackInteractionBridge.clearBackAction(backOwner)
        }
    }

    LaunchedEffect(playbackState, playerView, movie.movieId) {
        val stateRequest = when (val state = playbackState) {
            is PlaybackState.Playing -> state.request
            is PlaybackState.Paused -> state.request
            else -> null
        }
        if (
            !resumeApplied &&
            stateRequest?.mediaKind == PlaybackMediaKind.MOVIE &&
            stateRequest.channelId == movie.movieId
        ) {
            val player = playerView?.player ?: return@LaunchedEffect
            val resumePosition = movie.positionMs
                ?.takeIf { it > 5_000L && !movie.progressCompleted }
            if (resumePosition != null && player.currentPosition < 1_000L) {
                player.seekTo(resumePosition)
                currentPosition = resumePosition
            } else {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
            }
            duration = player.duration.takeIf { it > 0L } ?: duration
            resumeApplied = true
        }
    }

    LaunchedEffect(playerView, movie.movieId) {
        var saveTick = 0
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            val player = playerView?.player ?: continue
            currentPosition = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.takeIf { it > 0L } ?: duration
            saveTick += 1
            if (saveTick >= 5) {
                saveTick = 0
                featureRuntime.saveProgress(
                    sourceId = sourceId,
                    movieId = movie.movieId,
                    positionMs = currentPosition,
                    durationMs = duration.takeIf { it > 0L },
                )
            }
        }
    }

    app.ownplay.player.ui.OnDemandPlaybackSurface(
        runtime = runtime,
        contentKey = movie.movieId,
        title = movie.name,
        playbackState = playbackState,
        currentPositionMs = currentPosition,
        durationMs = duration,
        exitRequested = exitRequested,
        onExit = ::exitPlayback,
        onPlayerViewAvailable = { view -> playerView = view },
        onPlayerViewReleased = { view ->
            if (playerView === view) playerView = null
        },
        onSeekPositionChanged = { position -> currentPosition = position },
    )
}
'''

vod = vod[:start] + replacement + vod[end:]
VOD_PATH.write_text(vod)

TEST_PATH.write_text('''package app.ownplay.player.ui

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandPlaybackPresentationContractTest {
    @Test
    fun `shared on-demand surface owns custom controls without origin chrome`() {
        val shared = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")

        assertTrue(shared.contains("showNativeController = false"))
        assertTrue(shared.contains("ON_DEMAND_CONTROLS_AUTO_HIDE_MILLIS"))
        assertTrue(shared.contains("Slider("))
        assertTrue(shared.contains("scrubPositionMs"))
        assertTrue(shared.contains("scrubbing"))
        assertTrue(shared.contains("Icons.Filled.PlayArrow"))
        assertTrue(shared.contains("Icons.Filled.Pause"))
        assertFalse(shared.contains("ONLINE"))
        assertFalse(shared.contains("OFFLINE"))
        assertFalse(shared.contains("Local file"))
    }

    @Test
    fun `movie series and offline use one shared fullscreen presentation`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val moviePlayback = sourceBlockAfter(vod, "private fun VodPlaybackScreen(")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
        val seriesPlayback = sourceBlockAfter(series, "private fun SeriesPlaybackScreen(")
        val offline = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")
        val offlinePlayback = sourceBlockAfter(offline, "internal fun LibraryPlaybackScreen(")

        listOf(moviePlayback, seriesPlayback, offlinePlayback).forEach { playback ->
            assertTrue(playback.contains("OnDemandPlaybackSurface("))
            assertFalse(playback.contains("useController = true"))
            assertFalse(playback.contains("PlaybackOriginBadge"))
            assertFalse(playback.contains("OFFLINE"))
            assertFalse(playback.contains("Local file"))
        }
        assertFalse(moviePlayback.contains("Slider("))
        assertFalse(seriesPlayback.contains("Slider("))
        assertFalse(offlinePlayback.contains("Slider("))
    }
}
''')
