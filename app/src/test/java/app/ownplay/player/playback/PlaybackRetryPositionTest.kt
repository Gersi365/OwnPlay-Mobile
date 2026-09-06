package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRetryPositionTest {
    @Test
    fun manualMovieRetryRestoresFailurePosition() = runBlocking {
        val engine = FakeEngine(currentPositionMs = 42_000L)
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
        )

        controller.start(request(PlaybackMediaKind.MOVIE))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))

        controller.retry()

        assertEquals(42_000L, engine.seekPositionMs)
        assertTrue(controller.state.value is PlaybackState.Loading)
        controller.close()
    }

    @Test
    fun automaticMovieRetryRestoresFailurePosition() = runBlocking {
        val engine = FakeEngine(currentPositionMs = 42_000L)
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 1,
                initialDelayMillis = 0L,
                maxDelayMillis = 0L,
            ),
        )

        controller.start(request(PlaybackMediaKind.MOVIE))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))
        delay(1L)

        assertEquals(42_000L, engine.seekPositionMs)
        assertTrue(engine.prepareCount >= 2)
        controller.close()
    }

    @Test
    fun liveRetryNeverSeeksToPreviousPosition() = runBlocking {
        val engine = FakeEngine(currentPositionMs = 42_000L)
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
        )

        controller.start(request(PlaybackMediaKind.LIVE))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))

        controller.retry()

        assertNull(engine.seekPositionMs)
        controller.close()
    }

    private fun controller(
        engine: FakeEngine,
        retryPolicy: PlaybackRetryPolicy,
    ) = PlaybackController(
        resolveLocator = {
            PlaybackResolutionResult.Success(
                ResolvedPlaybackLocator(
                    value = "https://stream.example.test/media",
                    origin = ResolvedPlaybackOrigin.DIRECT,
                ),
            )
        },
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        retryPolicy = retryPolicy,
    )

    private fun request(mediaKind: PlaybackMediaKind) = PlaybackRequest(
        sourceId = "source",
        channelId = "content",
        mediaKind = mediaKind,
    )

    private class FakeEngine(
        private val currentPositionMs: Long,
    ) : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        var prepareCount: Int = 0
            private set
        var seekPositionMs: Long? = null
            private set

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            prepareCount += 1
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun currentPositionMs(): Long = currentPositionMs

        override fun seekTo(positionMs: Long) {
            seekPositionMs = positionMs
        }

        override fun suspendPlayback() = Unit
        override fun stop() = Unit
        override fun release() = Unit

        fun emitReady() = listener?.onReady()

        fun emitFailure(failure: PlaybackFailure) = listener?.onFailure(failure)
    }
}
