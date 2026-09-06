package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureSuspensionTest {
    @Test
    fun playerFailureReleasesResourcesWithoutClearingPreparedMedia() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(movieRequest())
        engine.emitReady()
        val hardStopsBeforeFailure = engine.stopCount

        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.UNSUPPORTED_MEDIA))

        assertTrue(controller.state.value is PlaybackState.Failed)
        assertEquals(1, engine.suspendCount)
        assertEquals(hardStopsBeforeFailure, engine.stopCount)
        assertNotNull(engine.preparedLocator)

        controller.stop()

        assertTrue(controller.state.value is PlaybackState.Idle)
        assertTrue(engine.stopCount > hardStopsBeforeFailure)
        assertNull(engine.preparedLocator)
        controller.close()
    }

    @Test
    fun networkLossReleasesResourcesWithoutClearingPreparedMedia() = runBlocking {
        val engine = FakeEngine()
        val networkState = MutableStateFlow(PlaybackNetworkState.AVAILABLE)
        val controller = controller(engine, networkState)

        controller.start(movieRequest())
        engine.emitReady()
        val hardStopsBeforeFailure = engine.stopCount

        networkState.value = PlaybackNetworkState.UNAVAILABLE

        val failed = controller.state.value as PlaybackState.Failed
        assertEquals(PlaybackFailureCategory.NETWORK_UNAVAILABLE, failed.failure.category)
        assertEquals(1, engine.suspendCount)
        assertEquals(hardStopsBeforeFailure, engine.stopCount)
        assertNotNull(engine.preparedLocator)
        controller.close()
    }

    @Test
    fun terminalPlayerFailureIsNotReclassifiedByLaterNetworkLoss() = runBlocking {
        val engine = FakeEngine()
        val networkState = MutableStateFlow(PlaybackNetworkState.AVAILABLE)
        val controller = controller(engine, networkState)

        controller.start(movieRequest())
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.UNSUPPORTED_MEDIA))

        networkState.value = PlaybackNetworkState.UNAVAILABLE

        val failed = controller.state.value as PlaybackState.Failed
        assertEquals(PlaybackFailureCategory.UNSUPPORTED_MEDIA, failed.failure.category)
        assertEquals(1, engine.suspendCount)
        controller.close()
    }

    private fun controller(
        engine: FakeEngine,
        networkState: MutableStateFlow<PlaybackNetworkState>? = null,
    ) = PlaybackController(
        resolveLocator = {
            PlaybackResolutionResult.Success(
                ResolvedPlaybackLocator(
                    value = "https://stream.example.test/movie",
                    origin = ResolvedPlaybackOrigin.DIRECT,
                ),
            )
        },
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
        networkState = networkState,
    )

    private fun movieRequest() = PlaybackRequest(
        sourceId = "source",
        channelId = "movie",
        mediaKind = PlaybackMediaKind.MOVIE,
    )

    private class FakeEngine : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        var preparedLocator: ResolvedPlaybackLocator? = null
            private set
        var stopCount: Int = 0
            private set
        var suspendCount: Int = 0
            private set

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            preparedLocator = locator
        }

        override fun play() = Unit
        override fun pause() = Unit

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
