package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.feature.live.domain.LiveCapacityMetadata
import app.ownplay.mobile.feature.playback.domain.LiveDvrReadHandle
import app.ownplay.mobile.feature.playback.domain.LivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSource
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineEvent
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineSelectionResult
import app.ownplay.mobile.feature.playback.domain.PlaybackProgressEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackPositionSnapshot
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.sources.domain.SourceId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDvrPlaybackIntegrationTest {
    private class Metadata : LiveCapacityMetadata {
        override fun accountKey(sourceId: String): String = "account:$sourceId"
        override fun maxConnections(sourceId: String): Int = 1
    }

    private class SegmentIngress : LiveDvrIngressClient {
        val openCount = AtomicInteger()
        val firstSegmentWritten = CompletableDeferred<Unit>()

        override suspend fun capture(
            uri: String,
            isHls: Boolean,
            startEpochMillis: Long,
            endEpochMillis: Long,
            output: OutputStream,
            onProgress: suspend (Long) -> Unit,
        ) {
            openCount.incrementAndGet()
            val boundaries = output as LiveDvrSegmentBoundaryOutput
            val packets = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0 }
            var sequence = 0L
            var total = 0L
            while (true) {
                val segment = LiveDvrIngressSegment(
                    identity = "integration-segment-$sequence",
                    durationMs = SEGMENT_DURATION_MS,
                    programTimeEpochMs = BASE_EPOCH_MS + sequence * SEGMENT_DURATION_MS,
                    discontinuitySequence = 0L,
                )
                if (boundaries.beginSegment(segment)) {
                    output.write(packets)
                    boundaries.endSegment(complete = true)
                    total += packets.size
                    onProgress(total)
                    firstSegmentWritten.complete(Unit)
                }
                sequence += 1L
                delay(20L)
            }
        }
    }

    private class ManagerBackedPlaybackEngine(
        private val manager: LiveDvrSessionManager,
    ) : PlaybackEngine, PlaybackProgressEngine {
        private var listener: ((PlaybackEngineEvent) -> Unit)? = null
        private var revision = 0L
        private var reader: LiveDvrReadHandle? = null
        var currentMedia: PreparedPlaybackMedia? = null
            private set
        var currentPositionMs = 0L

        override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
            this.listener = listener
        }

        override fun replace(media: PreparedPlaybackMedia): Long {
            reader?.close()
            val sessionId = checkNotNull(media.liveDvrSessionId)
            val offset = media.uri.substringAfter('?')
                .split('&')
                .firstOrNull { it.startsWith("offset=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
            reader = if (offset == null) {
                manager.openLiveReadHandle(sessionId)
            } else {
                manager.openReadHandle(sessionId, offset)
            }
            checkNotNull(reader) { "Media3 could not attach to the retained DVR session" }
            currentMedia = media
            currentPositionMs = 0L
            revision += 1L
            return revision
        }

        fun emitReady() {
            listener?.invoke(PlaybackEngineEvent(revision, readiness = PlaybackEngineReadiness.READY))
        }

        override fun selectAudioTrack(trackId: String?) = PlaybackEngineSelectionResult.UNSUPPORTED
        override fun selectSubtitleTrack(trackId: String?) = PlaybackEngineSelectionResult.UNSUPPORTED
        override fun play(): Boolean = true
        override fun pause(): Boolean = true
        override fun clear() {
            reader?.close()
            reader = null
            currentMedia = null
        }
        override fun release() = clear()
        override fun positionSnapshot() = PlaybackPositionSnapshot(currentPositionMs, null, false)
        override fun seekTo(positionMs: Long): Boolean {
            currentPositionMs = positionMs
            return true
        }
    }

    @Test
    fun headlessRecordingPlaybackPauseSeekAndGoLiveKeepOneSessionIngressAndReader() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-integration-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val ingress = SegmentIngress()
        val manager = LiveDvrSessionManager(
            sessionDirectory = directory,
            sourceResolver = sourceResolver(),
            mediaPreparer = mediaPreparer(),
            ingressClient = ingress,
            capacityCoordinator = capacity,
        )
        val recordingOutput = ByteArrayOutputStream()
        val recordingId = "scheduled-recording"
        val controllerEngine = ManagerBackedPlaybackEngine(manager)
        val controller = PlaybackSessionController(
            sourceResolver = sourceResolver(),
            mediaPreparer = mediaPreparer(),
            playbackEngine = controllerEngine,
            playbackProgressEngine = controllerEngine,
            liveCapacityCoordinator = capacity,
            liveDvrSessionGateway = manager,
            reconnectDispatcher = Dispatchers.Unconfined,
        )
        val target = PlaybackTarget.LiveChannel(SourceId(SOURCE), CHANNEL)
        assertTrue(capacity.acquireRecording(SOURCE, recordingId, CHANNEL, System.currentTimeMillis()).allowed)
        val recording = launch {
            manager.captureForRecording(
                sourceId = SOURCE,
                channelId = CHANNEL,
                recordingId = recordingId,
                endEpochMillis = Long.MAX_VALUE,
                media = preparedHls(),
                output = recordingOutput,
                onProgress = {},
            )
        }
        try {
            withTimeout(2_000L) { ingress.firstSegmentWritten.await() }
            withTimeout(2_000L) {
                while ((manager.snapshot(SOURCE, CHANNEL)?.timeline?.size ?: 0) < 5) delay(5L)
            }

            controller.activateLiveChannel(target)
            controllerEngine.emitReady()
            val initial = manager.snapshot(SOURCE, CHANNEL)!!
            val initialEntries = initial.timeline.toList()
            assertTrue(initial.playbackAttached)
            assertEquals(setOf(recordingId), initial.recordingIds)
            assertEquals(1, initial.providerIngressOpenCount)
            assertEquals(1, initial.activeReaderCount)

            assertTrue(controller.pause())
            controllerEngine.currentPositionMs = 3_500L
            assertTrue(controller.play())
            assertTrue(controller.seekLiveDvrToEpoch(BASE_EPOCH_MS + 15_000L))
            val mappedEntry = initialEntries[1]
            assertEquals(mappedEntry.startOffset, queryOffset(controllerEngine.currentMedia!!.uri))
            assertEquals(mappedEntry.startEpochMillis, controllerEngine.currentMedia!!.liveDvrStartEpochMillis)

            assertTrue(controller.goLive())
            repeat(4) {
                assertTrue(controller.pause())
                assertTrue(controller.play())
                assertTrue(controller.goLive())
            }

            val afterCycles = manager.snapshot(SOURCE, CHANNEL)!!
            assertEquals(initial.sessionId, afterCycles.sessionId)
            assertEquals(1, afterCycles.providerIngressOpenCount)
            assertEquals(1, ingress.openCount.get())
            assertEquals(1, afterCycles.activeReaderCount)
            assertEquals(setOf(recordingId), afterCycles.recordingIds)
            assertEquals(initialEntries, afterCycles.timeline.take(initialEntries.size))
            assertTrue(recordingOutput.size() > 0)

            val timeline = controller.liveDvrTimelineSnapshot()
            assertNotNull(timeline)
            assertEquals(initial.sessionId, timeline?.sessionId)
        } finally {
            recording.cancelAndJoin()
            manager.completeRecording(recordingId)
            controller.clear()
            manager.close()
            directory.deleteRecursively()
        }
    }

    private fun sourceResolver() = object : LivePlaybackSourceResolver {
        override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource =
            LivePlaybackSource.Direct("https://provider.invalid/live.m3u8")
    }

    private fun mediaPreparer() = object : LivePlaybackMediaPreparer {
        override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia = preparedHls()
    }

    private fun preparedHls() = PreparedPlaybackMedia(
        uri = "https://provider.invalid/live.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        usesProviderConnection = true,
    )

    private fun queryOffset(uri: String): Long = uri.substringAfter('?')
        .split('&')
        .first { it.startsWith("offset=") }
        .substringAfter('=').toLong()

    private companion object {
        const val SOURCE = "source"
        const val CHANNEL = "channel"
        const val SEGMENT_DURATION_MS = 10_000L
        const val BASE_EPOCH_MS = 1_700_000_000_000L
    }
}
