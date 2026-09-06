package app.ownplay.player.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryExitRaceTest {
    @Test
    fun stopWhileManualRetryResolutionIsInFlightKeepsPlaybackIdle() = runBlocking {
        val retryGate = CompletableDeferred<PlaybackResolutionResult>()
        var resolveCount = 0
        val engine = FakeEngine()
        val controller = PlaybackController(
            resolveLocator = {
                resolveCount += 1
                if (resolveCount == 1) {
                    success()
                } else {
                    retryGate.await()
                }
            },
            engine = engine,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
        )

        controller.start(movieRequest())
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))

        controller.retry()
        assertEquals(2, resolveCount)
        controller.stop()
        assertEquals(PlaybackState.Idle, controller.state.value)

        retryGate.complete(success())
        delay(1L)

        assertEquals(PlaybackState.Idle, controller.state.value)
        assertEquals(1, engine.prepareCount)
        controller.close()
    }

    @Test
    fun retryAfterStopIsNoOp() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(movieRequest())
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))
        controller.stop()

        val prepareCountAfterStop = engine.prepareCount
        controller.retry()

        assertEquals(PlaybackState.Idle, controller.state.value)
        assertEquals(prepareCountAfterStop, engine.prepareCount)
        controller.close()
    }

    @Test
    fun stopCancelsScheduledAutomaticRetry() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackController(
            resolveLocator = { success() },
            engine = engine,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 1,
                initialDelayMillis = 10L,
                maxDelayMillis = 10L,
            ),
        )

        controller.start(movieRequest())
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))
        controller.stop()

        delay(20L)

        assertEquals(PlaybackState.Idle, controller.state.value)
        assertEquals(1, engine.prepareCount)
        controller.close()
    }

    private fun controller(engine: FakeEngine) = PlaybackController(
        resolveLocator = { success() },
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
    )

    private fun movieRequest() = PlaybackRequest(
        sourceId = "source",
        channelId = "movie",
        mediaKind = PlaybackMediaKind.MOVIE,
    )

    private fun success() = PlaybackResolutionResult.Success(
        ResolvedPlaybackLocator(
            value = "https://stream.example.test/movie",
            origin = ResolvedPlaybackOrigin.DIRECT,
        ),
    )

    private class FakeEngine : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        var prepareCount: Int = 0
            private set

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            prepareCount += 1
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun suspendPlayback() = Unit
        override fun stop() = Unit
        override fun release() = Unit

        fun emitReady() = listener?.onReady()

        fun emitFailure(failure: PlaybackFailure) = listener?.onFailure(failure)
    }
}
