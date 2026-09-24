package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlaybackProgressControllerTest {
    @Test
    fun progressPolicyRequiresKnownPositiveDurationAndResumableInteriorPosition() {
        assertNull(
            LibraryPlaybackProgressPolicy.checkpoint(
                PlaybackPositionSnapshot(
                    positionMs = 5_000L,
                    durationMs = null,
                    ended = false,
                ),
            ),
        )
        assertNull(
            LibraryPlaybackProgressPolicy.checkpoint(
                PlaybackPositionSnapshot(
                    positionMs = 0L,
                    durationMs = 100_000L,
                    ended = false,
                ),
            ),
        )

        val progress = LibraryPlaybackProgressPolicy.checkpoint(
            PlaybackPositionSnapshot(
                positionMs = 40_000L,
                durationMs = 100_000L,
                ended = false,
            ),
        )
        requireNotNull(progress)
        assertEquals(40_000L, progress.positionMs)
        assertFalse(progress.completed)
        assertEquals(40_000L, LibraryPlaybackProgressPolicy.resumePosition(progress))
    }

    @Test
    fun endedSnapshotIsPersistedCompletedAndDoesNotResume() {
        val progress = LibraryPlaybackProgressPolicy.checkpoint(
            PlaybackPositionSnapshot(
                positionMs = 95_000L,
                durationMs = 100_000L,
                ended = true,
            ),
        )

        requireNotNull(progress)
        assertEquals(100_000L, progress.positionMs)
        assertTrue(progress.completed)
        assertNull(LibraryPlaybackProgressPolicy.resumePosition(progress))
    }

    @Test
    fun libraryActivationRestoresIncompleteProgressWithoutReplacingSameTargetTwice() = runBlocking {
        val engine = FakeEngine()
        val store = FakeProgressStore(
            loaded = LibraryPlaybackProgress(
                positionMs = 42_000L,
                durationMs = 120_000L,
                completed = false,
            ),
        )
        val controller = controller(engine, store)
        val target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")

        controller.activateLibraryMedia(target)
        controller.activateLibraryMedia(target)

        assertEquals(listOf(42_000L), engine.seekPositions)
        assertEquals(1, engine.replaceCount)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
    }

    @Test
    fun clearingLibraryPlaybackCheckpointsCurrentPosition() = runBlocking {
        val engine = FakeEngine()
        val store = FakeProgressStore()
        val controller = controller(engine, store)
        val target = PlaybackTarget.Episode(SourceId("source-a"), "episode-a")

        controller.activateLibraryMedia(target)
        engine.snapshot = PlaybackPositionSnapshot(
            positionMs = 61_000L,
            durationMs = 120_000L,
            ended = false,
        )
        controller.clear()

        val recorded = store.recorded.single()
        assertEquals(target, recorded.first)
        assertEquals(61_000L, recorded.second.positionMs)
        assertEquals(120_000L, recorded.second.durationMs)
        assertFalse(recorded.second.completed)
    }

    @Test
    fun switchingFromLibraryToLiveCheckpointsLibraryBeforeMediaReplacement() = runBlocking {
        val engine = FakeEngine()
        val store = FakeProgressStore()
        val controller = controller(engine, store)
        val movie = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")

        controller.activateLibraryMedia(movie)
        engine.snapshot = PlaybackPositionSnapshot(
            positionMs = 30_000L,
            durationMs = 90_000L,
            ended = false,
        )
        controller.activateLiveChannel(
            PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"),
        )

        assertEquals(movie, store.recorded.single().first)
        assertEquals(30_000L, store.recorded.single().second.positionMs)
        assertEquals(2, engine.replaceCount)
    }

    @Test
    fun naturalLibraryEndRecordsCompletionWithoutMarkingSessionUnavailable() = runBlocking {
        val engine = FakeEngine()
        val store = FakeProgressStore()
        val controller = controller(engine, store)
        val target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a")

        controller.activateLibraryMedia(target)
        engine.snapshot = PlaybackPositionSnapshot(
            positionMs = 120_000L,
            durationMs = 120_000L,
            ended = true,
        )
        engine.emitEnded()

        val recorded = store.recorded.single().second
        assertTrue(recorded.completed)
        assertEquals(120_000L, recorded.positionMs)
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
    }

    private fun controller(
        engine: FakeEngine,
        store: FakeProgressStore,
    ) = PlaybackSessionController(
        sourceResolver = FakeLiveResolver(),
        mediaPreparer = FakeLivePreparer(),
        playbackEngine = engine,
        libraryMediaResolver = FakeLibraryResolver(),
        playbackProgressEngine = engine,
        libraryProgressStore = store,
    )

    private class FakeLiveResolver : LivePlaybackSourceResolver {
        override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource =
            LivePlaybackSource.Direct("https://provider.example/live/${target.channelId}")
    }

    private class FakeLivePreparer : LivePlaybackMediaPreparer {
        override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia =
            PreparedPlaybackMedia("https://provider.example/live/prepared")
    }

    private class FakeLibraryResolver : LibraryPlaybackMediaResolver {
        override suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia =
            PreparedPlaybackMedia("https://provider.example/library/prepared")
    }

    private class FakeProgressStore(
        var loaded: LibraryPlaybackProgress? = null,
    ) : LibraryPlaybackProgressStore {
        val recorded = mutableListOf<Pair<PlaybackTarget.Library, LibraryPlaybackProgress>>()
        var closed = false

        override suspend fun load(target: PlaybackTarget.Library): LibraryPlaybackProgress? = loaded

        override fun record(
            target: PlaybackTarget.Library,
            progress: LibraryPlaybackProgress,
        ) {
            recorded += target to progress
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeEngine : PlaybackEngine, PlaybackProgressEngine {
        private var listener: ((PlaybackEngineEvent) -> Unit)? = null
        private var revision = 0L
        var replaceCount = 0
            private set
        val seekPositions = mutableListOf<Long>()
        var snapshot: PlaybackPositionSnapshot? = PlaybackPositionSnapshot(
            positionMs = 0L,
            durationMs = 120_000L,
            ended = false,
        )

        override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
            this.listener = listener
        }

        override fun replace(media: PreparedPlaybackMedia): Long {
            replaceCount += 1
            revision += 1L
            snapshot = PlaybackPositionSnapshot(
                positionMs = 0L,
                durationMs = 120_000L,
                ended = false,
            )
            return revision
        }

        override fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun positionSnapshot(): PlaybackPositionSnapshot? = snapshot

        override fun seekTo(positionMs: Long): Boolean {
            seekPositions += positionMs
            snapshot = snapshot?.copy(positionMs = positionMs)
            return true
        }

        override fun clear() = Unit

        override fun release() = Unit

        fun emitEnded() {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    readiness = PlaybackEngineReadiness.ENDED,
                ),
            )
        }

        fun emitFailure(failureClass: PlaybackEngineFailureClass) {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    readiness = PlaybackEngineReadiness.FAILED,
                    failureClass = failureClass,
                ),
            )
        }
    }
}
