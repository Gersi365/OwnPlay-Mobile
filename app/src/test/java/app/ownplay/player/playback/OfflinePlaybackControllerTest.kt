package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackControllerTest {
    @Test
    fun localDownloadStartsWithoutNetworkAndIgnoresNetworkLoss() = runBlocking {
        val localLocator = ResolvedPlaybackLocator(
            value = "file:///data/user/0/app.ownplay.player/files/offline/movie.mp4",
            origin = ResolvedPlaybackOrigin.LOCAL_DOWNLOAD,
        )
        val engine = FakeEngine()
        val network = MutableStateFlow(PlaybackNetworkState.UNAVAILABLE)
        var remoteResolveCount = 0
        val controller = PlaybackController(
            resolveLocator = {
                remoteResolveCount += 1
                PlaybackResolutionResult.Success(
                    ResolvedPlaybackLocator(
                        value = "https://stream.example.test/movie.mp4",
                        origin = ResolvedPlaybackOrigin.XTREAM_VOD,
                    ),
                )
            },
            engine = engine,
            resolveOfflineLocator = { localLocator },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            networkState = network,
        )

        controller.start(
            PlaybackRequest(
                sourceId = "source",
                channelId = "movie",
                mediaKind = PlaybackMediaKind.MOVIE,
            ),
        )

        assertEquals(0, remoteResolveCount)
        assertEquals(localLocator, engine.preparedLocator)
        assertEquals(ResolvedPlaybackOrigin.LOCAL_DOWNLOAD, controller.resolvedOrigin.value)
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)

        network.value = PlaybackNetworkState.AVAILABLE
        network.value = PlaybackNetworkState.UNAVAILABLE

        assertTrue(controller.state.value is PlaybackState.Playing)
        assertEquals(localLocator, engine.preparedLocator)
        assertEquals(ResolvedPlaybackOrigin.LOCAL_DOWNLOAD, controller.resolvedOrigin.value)
        controller.close()
        assertNull(controller.resolvedOrigin.value)
    }

    @Test
    fun remoteVodExposesProviderOrigin() = runBlocking {
        val remoteLocator = ResolvedPlaybackLocator(
            value = "https://stream.example.test/movie.mp4",
            origin = ResolvedPlaybackOrigin.XTREAM_VOD,
        )
        val engine = FakeEngine()
        val network = MutableStateFlow(PlaybackNetworkState.AVAILABLE)
        val controller = PlaybackController(
            resolveLocator = { PlaybackResolutionResult.Success(remoteLocator) },
            engine = engine,
            resolveOfflineLocator = { null },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            networkState = network,
        )

        controller.start(
            PlaybackRequest(
                sourceId = "source",
                channelId = "movie",
                mediaKind = PlaybackMediaKind.MOVIE,
            ),
        )

        assertEquals(remoteLocator, engine.preparedLocator)
        assertEquals(ResolvedPlaybackOrigin.XTREAM_VOD, controller.resolvedOrigin.value)
        controller.stop()
        assertNull(controller.resolvedOrigin.value)
        controller.close()
    }

    private class FakeEngine : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        var preparedLocator: ResolvedPlaybackLocator? = null
            private set

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            preparedLocator = locator
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun release() = Unit

        fun emitReady() = listener?.onReady()
    }
}
