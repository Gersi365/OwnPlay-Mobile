package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionOwnershipTest {
    @Test
    fun matchingDisposedSessionStopsPlayback() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val request = movieRequest("movie-a")

        controller.start(request)
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)

        controller.stopIfCurrent(
            sourceId = request.sourceId,
            channelId = request.channelId,
            mediaKind = request.mediaKind,
        )

        assertEquals(PlaybackState.Idle, controller.state.value)
        controller.close()
    }

    @Test
    fun staleDisposedSessionCannotStopNewPlayback() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val oldRequest = movieRequest("movie-old")
        val newRequest = movieRequest("movie-new")

        controller.start(oldRequest)
        engine.emitReady()
        controller.start(newRequest)
        engine.emitReady()

        controller.stopIfCurrent(
            sourceId = oldRequest.sourceId,
            channelId = oldRequest.channelId,
            mediaKind = oldRequest.mediaKind,
        )

        val playing = controller.state.value as PlaybackState.Playing
        assertEquals(newRequest, playing.request)
        controller.close()
    }

    @Test
    fun matchingSuspendedSessionCanBeDisposedWithoutResume() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val request = movieRequest("movie-suspended")

        controller.start(request)
        engine.emitReady()
        controller.suspendForBackground()
        assertTrue(controller.state.value is PlaybackState.Paused)

        controller.stopIfCurrent(
            sourceId = request.sourceId,
            channelId = request.channelId,
            mediaKind = request.mediaKind,
        )
        controller.resumeAfterBackground()

        assertEquals(PlaybackState.Idle, controller.state.value)
        controller.close()
    }

    @Test
    fun disposedSessionCleanupAfterControllerReleaseIsNoOp() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(engine)
        val request = movieRequest("movie-released")

        controller.start(request)
        engine.emitReady()
        controller.close()

        controller.stopIfCurrent(
            sourceId = request.sourceId,
            channelId = request.channelId,
            mediaKind = request.mediaKind,
        )

        assertEquals(PlaybackState.Idle, controller.state.value)
    }

    private fun controller(engine: FakeEngine) = PlaybackController(
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
    )

    private fun movieRequest(channelId: String) = PlaybackRequest(
        sourceId = "source",
        channelId = channelId,
        mediaKind = PlaybackMediaKind.MOVIE,
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
    }
}
