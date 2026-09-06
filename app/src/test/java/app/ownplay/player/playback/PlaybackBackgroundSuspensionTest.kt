package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBackgroundSuspensionTest {
    @Test
    fun playingSessionReleasesResourcesAndRestoresPlayingAfterBackground() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val request = request()

        controller.start(request)
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)

        val hardStopsBeforeBackground = engine.stopCount
        controller.suspendForBackground()

        val suspended = controller.state.value as PlaybackState.Paused
        assertEquals(request, suspended.request)
        assertEquals(1, engine.suspendCount)
        assertEquals(hardStopsBeforeBackground, engine.stopCount)
        assertTrue(engine.preparedLocator != null)

        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.UNKNOWN))
        assertTrue(controller.state.value is PlaybackState.Paused)

        controller.resumeAfterBackground()
        assertTrue(controller.state.value is PlaybackState.Loading)
        assertTrue(engine.preparedLocator != null)
        assertNull(engine.seekPositionMs)

        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)
        controller.close()
    }

    @Test
    fun pausedSessionReleasesResourcesAndRestoresPausedAfterBackground() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val request = request()

        controller.start(request)
        engine.emitReady()
        controller.pause()
        assertTrue(controller.state.value is PlaybackState.Paused)

        controller.suspendForBackground()
        assertTrue(controller.state.value is PlaybackState.Paused)
        assertEquals(1, engine.suspendCount)
        assertTrue(engine.preparedLocator != null)

        controller.resumeAfterBackground()
        assertTrue(controller.state.value is PlaybackState.Loading)
        assertTrue(engine.pauseCount > 0)
        assertNull(engine.seekPositionMs)

        engine.emitReady()
        val restored = controller.state.value as PlaybackState.Paused
        assertEquals(request, restored.request)
        controller.close()
    }

    @Test
    fun onDemandSessionRestoresPositionAfterBackground() = runBlocking {
        val engine = FakeEngine(currentPositionMs = 42_000L)
        val controller = controller(engine)
        val request = request(PlaybackMediaKind.MOVIE)

        controller.start(request)
        engine.emitReady()
        controller.suspendForBackground()
        controller.resumeAfterBackground()

        assertEquals(1, engine.suspendCount)
        assertEquals(42_000L, engine.seekPositionMs)
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)
        controller.close()
    }

    @Test
    fun networkLossWhileSuspendedFailsBeforePreparingDecoderAgainOnResume() = runBlocking {
        val engine = FakeEngine()
        val networkState = MutableStateFlow(PlaybackNetworkState.AVAILABLE)
        val controller = controller(engine, networkState)

        controller.start(request())
        engine.emitReady()
        val preparesBeforeBackground = engine.prepareCount
        controller.suspendForBackground()
        networkState.value = PlaybackNetworkState.UNAVAILABLE

        controller.resumeAfterBackground()

        val failed = controller.state.value as PlaybackState.Failed
        assertEquals(PlaybackFailureCategory.NETWORK_UNAVAILABLE, failed.failure.category)
        assertEquals(preparesBeforeBackground, engine.prepareCount)
        controller.close()
    }

    @Test
    fun freshStartStillUsesHardStop() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(request())

        assertTrue(engine.stopCount > 0)
        assertEquals(0, engine.suspendCount)
        controller.close()
    }

    @Test
    fun resumeWithoutBackgroundSuspensionIsNoOpForActiveLivePlayback() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(request())
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)

        val preparesBeforeResume = engine.prepareCount
        val stopsBeforeResume = engine.stopCount
        val suspendsBeforeResume = engine.suspendCount

        controller.resumeAfterBackground()

        assertTrue(controller.state.value is PlaybackState.Playing)
        assertEquals(preparesBeforeResume, engine.prepareCount)
        assertEquals(stopsBeforeResume, engine.stopCount)
        assertEquals(suspendsBeforeResume, engine.suspendCount)
        controller.close()
    }

    private fun controller(
        engine: FakeEngine,
        networkState: MutableStateFlow<PlaybackNetworkState>? = null,
    ) = PlaybackController(
        resolveLocator = {
            PlaybackResolutionResult.Success(
                ResolvedPlaybackLocator(
                    value = "https://stream.example.test/live",
                    origin = ResolvedPlaybackOrigin.DIRECT,
                ),
            )
        },
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        networkState = networkState,
    )

    private fun request(mediaKind: PlaybackMediaKind = PlaybackMediaKind.LIVE) = PlaybackRequest(
        sourceId = "source",
        channelId = "channel",
        mediaKind = mediaKind,
    )

    private class FakeEngine(
        private val currentPositionMs: Long? = 42_000L,
    ) : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        var preparedLocator: ResolvedPlaybackLocator? = null
            private set
        var prepareCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var suspendCount: Int = 0
            private set
        var pauseCount: Int = 0
            private set
        var seekPositionMs: Long? = null
            private set

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            prepareCount += 1
            preparedLocator = locator
        }

        override fun play() = Unit

        override fun pause() {
            pauseCount += 1
        }

        override fun currentPositionMs(): Long? = currentPositionMs

        override fun seekTo(positionMs: Long) {
            seekPositionMs = positionMs
        }

        override fun suspendPlayback() {
            suspendCount += 1
        }

        override fun stop() {
            stopCount += 1
            preparedLocator = null
        }

        override fun release() = Unit

        fun emitReady() = listener?.onReady()

        fun emitFailure(failure: PlaybackFailure) = listener?.onFailure(failure)
    }
}
