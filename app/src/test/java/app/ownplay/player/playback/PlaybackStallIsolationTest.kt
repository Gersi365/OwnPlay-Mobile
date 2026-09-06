package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallIsolationTest {
    @Test
    fun stopDuringBufferingCancelsWatchdogAndKeepsPlaybackIdle() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(request("first"))
        engine.emitReady()
        engine.emitBuffering()
        assertTrue((controller.state.value as PlaybackState.Playing).buffering)

        controller.stop()
        assertTrue(controller.state.value is PlaybackState.Idle)
        delay(30L)

        assertTrue(controller.state.value is PlaybackState.Idle)
        controller.close()
    }

    @Test
    fun channelSwitchDuringBufferingInvalidatesOldWatchdog() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)

        controller.start(request("first"))
        engine.emitReady()
        engine.emitBuffering()

        controller.start(request("second"))
        delay(30L)

        val loading = controller.state.value as PlaybackState.Loading
        assertEquals("second", loading.request.channelId)
        controller.close()
    }

    private fun controller(engine: FakeEngine) = PlaybackController(
        resolveLocator = { request ->
            PlaybackResolutionResult.Success(
                ResolvedPlaybackLocator(
                    value = "https://stream.example.test/${request.channelId}",
                    origin = ResolvedPlaybackOrigin.DIRECT,
                ),
            )
        },
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        loadingTimeoutMillis = 1_000L,
        rebufferTimeoutMillis = 10L,
        retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
    )

    private fun request(channelId: String) = PlaybackRequest(
        sourceId = "source",
        channelId = channelId,
    )

    private class FakeEngine : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun release() = Unit

        fun emitReady() = listener?.onReady()
        fun emitBuffering() = listener?.onBuffering()
    }
}
