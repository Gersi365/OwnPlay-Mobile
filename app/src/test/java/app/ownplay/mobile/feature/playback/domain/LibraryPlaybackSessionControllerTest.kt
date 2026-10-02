package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaybackSessionControllerTest {
    @Test
    fun explicitOfflineSelectionReplacesOnlineMediaInTheSameEngine() = runBlocking {
        val resolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(resolver, engine)
        val online = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")
        val offline = online.copy(offlineDownloadId = "download-a")

        controller.activateLibraryMedia(online)
        engine.emitReady()
        controller.activateLibraryMedia(offline)

        assertEquals(offline, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(listOf(online, offline), resolver.resolvedTargets)
        assertEquals(2, engine.replacedMedia.size)
    }

    @Test
    fun offlinePipReturnPreservesTargetWithoutReplacingMedia() = runBlocking {
        val resolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(resolver, engine)
        val offline = PlaybackTarget.Episode(SourceId("source-a"), "episode-a", "download-a")

        controller.activateLibraryMedia(offline)
        engine.emitReady()
        controller.onPictureInPictureModeChanged(true)
        controller.onPictureInPictureModeChanged(false)

        assertEquals(offline, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
        assertEquals(listOf(offline), resolver.resolvedTargets)
    }

    @Test
    fun movieActivationUsesSharedEngineAndStartsFullscreen() = runBlocking {
        val liveResolver = FakeLiveResolver()
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            sourceResolver = liveResolver,
            mediaPreparer = FakeLivePreparer(),
            playbackEngine = engine,
            libraryMediaResolver = libraryResolver,
        )
        val target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")

        controller.activateLibraryMedia(target)

        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)
        assertEquals(listOf(target), libraryResolver.resolvedTargets)
        assertTrue(liveResolver.resolvedTargets.isEmpty())
        assertEquals(1, engine.replacedMedia.size)
        assertFalse(controller.state.value.toString().contains("provider.example"))
    }

    @Test
    fun episodeReplacesMovieAsTheSingleActiveTarget() = runBlocking {
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(libraryResolver, engine)
        val movie = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")
        val episode = PlaybackTarget.Episode(SourceId("source-a"), "episode-a")

        controller.activateLibraryMedia(movie)
        controller.activateLibraryMedia(episode)

        assertEquals(episode, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(2, engine.replacedMedia.size)
        assertEquals(listOf(movie, episode), libraryResolver.resolvedTargets)
    }

    @Test
    fun sameLibraryTargetReturnsFullscreenWithoutReplacingMediaAgain() = runBlocking {
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(libraryResolver, engine)
        val target = PlaybackTarget.Episode(SourceId("source-a"), "episode-a")

        controller.activateLibraryMedia(target)
        engine.emitReady()
        controller.enterPictureInPicture()
        controller.activateLibraryMedia(target)

        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
        assertEquals(1, engine.replacedMedia.size)
        assertEquals(1, libraryResolver.resolvedTargets.size)
    }

    @Test
    fun libraryTargetRevalidationClearsUnavailableMedia() = runBlocking {
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(libraryResolver, engine)
        val target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")

        controller.activateLibraryMedia(target)
        engine.emitReady()
        libraryResolver.unavailableContentId = "movie-a"
        controller.revalidateActiveTarget()

        assertNull(controller.state.value.target)
        assertEquals(PlaybackReadiness.IDLE, controller.state.value.readiness)
        assertEquals(2, engine.clearCount)
    }

    @Test
    fun vodSpeedIsSessionOnlyAndResetsForTheNextMediaTarget() = runBlocking {
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(libraryResolver, engine)
        val movie = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")
        val episode = PlaybackTarget.Episode(SourceId("source-a"), "episode-a")

        controller.activateLibraryMedia(movie)
        assertTrue(controller.setSpeed(1.5f))
        assertEquals(1.5f, controller.state.value.speed)
        assertEquals(1.5f, engine.speed)

        controller.activateLibraryMedia(episode)

        assertEquals(PlaybackSpeedPolicy.DEFAULT_SPEED, controller.state.value.speed)
        assertEquals(PlaybackSpeedPolicy.DEFAULT_SPEED, engine.speed)
        assertFalse(controller.setSpeed(1.1f))
    }

    @Test
    fun pictureInPictureExitReturnsLibraryMediaToFullscreen() = runBlocking {
        val libraryResolver = FakeLibraryResolver()
        val engine = FakeEngine()
        val controller = controller(libraryResolver, engine)
        val target = PlaybackTarget.Episode(SourceId("source-a"), "episode-a")

        controller.activateLibraryMedia(target)
        engine.emitReady()
        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(false)

        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
    }

    private fun controller(
        libraryResolver: FakeLibraryResolver,
        engine: FakeEngine,
    ): PlaybackSessionController = PlaybackSessionController(
        sourceResolver = FakeLiveResolver(),
        mediaPreparer = FakeLivePreparer(),
        playbackEngine = engine,
        libraryMediaResolver = libraryResolver,
    )

    private class FakeLiveResolver : LivePlaybackSourceResolver {
        val resolvedTargets = mutableListOf<PlaybackTarget.LiveChannel>()

        override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource? {
            resolvedTargets += target
            return LivePlaybackSource.Direct("https://live.example/${target.channelId}")
        }
    }

    private class FakeLivePreparer : LivePlaybackMediaPreparer {
        override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia =
            PreparedPlaybackMedia("https://live.example/prepared")
    }

    private class FakeLibraryResolver : LibraryPlaybackMediaResolver {
        val resolvedTargets = mutableListOf<PlaybackTarget.Library>()
        var unavailableContentId: String? = null

        override suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia? {
            resolvedTargets += target
            val contentId = when (target) {
                is PlaybackTarget.Movie -> target.movieId
                is PlaybackTarget.Episode -> target.episodeId
            }
            if (contentId == unavailableContentId) return null
            return PreparedPlaybackMedia(
                uri = "https://provider.example/private/$contentId",
            )
        }
    }

    private class FakeEngine : PlaybackEngine {
        val replacedMedia = mutableListOf<PreparedPlaybackMedia>()
        var clearCount = 0
        var speed = PlaybackSpeedPolicy.DEFAULT_SPEED
        private var revision = 0L
        private var listener: ((PlaybackEngineEvent) -> Unit)? = null

        override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
            this.listener = listener
        }

        override fun replace(media: PreparedPlaybackMedia): Long {
            revision += 1L
            replacedMedia += media
            return revision
        }

        override fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun setSpeed(speed: Float): Boolean {
            this.speed = speed
            return true
        }

        override fun clear() {
            clearCount += 1
        }

        override fun release() = Unit

        fun emitReady() {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    readiness = PlaybackEngineReadiness.READY,
                ),
            )
        }
    }
}
