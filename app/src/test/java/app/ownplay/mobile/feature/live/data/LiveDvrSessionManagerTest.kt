package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.feature.live.domain.LiveCapacityMetadata
import app.ownplay.mobile.feature.playback.domain.LivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSource
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackAlternative
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDvrSessionManagerTest {
    private class FakeIngress : LiveDvrIngressClient {
        val openCount = AtomicInteger()
        val started = CompletableDeferred<Unit>()

        override suspend fun capture(
            uri: String,
            isHls: Boolean,
            startEpochMillis: Long,
            endEpochMillis: Long,
            output: java.io.OutputStream,
            onProgress: suspend (Long) -> Unit,
        ) {
            openCount.incrementAndGet()
            var total = 0L
            val packet = ByteArray(188) { index -> if (index == 0) 0x47 else index.toByte() }
            try {
                while (true) {
                    output.write(packet)
                    total += packet.size
                    onProgress(total)
                    started.complete(Unit)
                    delay(5L)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private class FakeHlsIngress : LiveDvrIngressClient {
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
            val packet = ByteArray(188 * 4) { index -> if (index % 188 == 0) 0x47 else 0 }
            var total = 0L
            var sequence = 0L
            while (true) {
                val segment = LiveDvrIngressSegment(
                    identity = "hls-segment-$sequence",
                    durationMs = 10_000L,
                    programTimeEpochMs = BASE_EPOCH_MS + sequence * 10_000L,
                    discontinuitySequence = 0L,
                )
                if (boundaries.beginSegment(segment)) {
                    output.write(packet)
                    boundaries.endSegment(complete = true)
                    total += packet.size
                    onProgress(total)
                    firstSegmentWritten.complete(Unit)
                }
                sequence += 1L
                delay(20L)
            }
        }
    }

    private class FakeResolver : LivePlaybackSourceResolver {
        override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource =
            LivePlaybackSource.Direct("https://provider.invalid/live.ts")
    }

    @Test
    fun storagePolicyReservesVolumeHeadroomAndAccountsForPinnedOutputCopies() {
        val total = 10L * 1024L * 1024L * 1024L
        val reserve = LiveDvrStoragePolicy.requiredHeadroom(total)
        val oneCopySpace = LiveDvrStorageSpace(reserve + 2_000L, total)

        assertTrue(LiveDvrStoragePolicy.canStart(LiveDvrStorageSpace(reserve, total)))
        assertTrue(LiveDvrStoragePolicy.canWrite(oneCopySpace, incomingBytes = 2_000L, recordingOutputCount = 0))
        assertFalse(LiveDvrStoragePolicy.canWrite(oneCopySpace, incomingBytes = 2_000L, recordingOutputCount = 1))
        assertFalse(LiveDvrStoragePolicy.canStart(LiveDvrStorageSpace(reserve - 1L, total)))
    }

    @Test
    fun processRecreationLeavesAnUnindexedRetainedPrefixUntouched() {
        val directory = Files.createTempDirectory("ownplay-dvr-process-recovery-test").toFile()
        val retainedPrefix = File(directory, "interrupted-session.ts")
        val validPrefix = ByteArray(188 * 8) { index -> if (index % 188 == 0) 0x47 else 0 }
        retainedPrefix.writeBytes(validPrefix)
        val manager = manager(directory, FakeIngress(), LiveCapacityCoordinator(Metadata()))

        try {
            assertTrue(retainedPrefix.exists())
            assertTrue(retainedPrefix.readBytes().contentEquals(validPrefix))
        } finally {
            manager.close()
            directory.deleteRecursively()
        }
    }

    private class FakePreparer : LivePlaybackMediaPreparer {
        override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia =
            PreparedPlaybackMedia(
                uri = "https://provider.invalid/live.ts",
                mimeType = LiveDvrSessionManager.MPEG_TS_MIME_TYPE,
                usesProviderConnection = true,
            )
    }

    private class Metadata : LiveCapacityMetadata {
        override fun accountKey(sourceId: String): String = "account:$sourceId"
        override fun maxConnections(sourceId: String): Int = 1
    }

    @Test
    fun playbackAndRecordPinShareOneIngressAndKeepItHeadlessUntilFinalization() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val ingress = FakeIngress()
        val manager = manager(directory, ingress, capacity)
        try {
            assertTrue(capacity.acquirePlayback(SOURCE, CHANNEL).allowed)
            val playbackMedia = manager.attachPlayback(SOURCE, CHANNEL, prepared())
            assertNotNull(playbackMedia)
            withTimeout(2_000L) { ingress.started.await() }
            val sessionId = playbackMedia!!.uri.substringAfter("://").substringBefore('/')
            val playbackReader = manager.openReadHandle(sessionId, 0L)
            assertNotNull(playbackReader)
            val retainedPacket = ByteArray(188)
            assertEquals(188, playbackReader!!.read(retainedPacket, 0, retainedPacket.size))
            assertEquals(0x47.toByte(), retainedPacket.first())
            playbackReader.close()
            val liveEdgeReader = manager.openLiveReadHandle(sessionId)
            assertNotNull(liveEdgeReader)
            val liveEdgePacket = ByteArray(188)
            assertEquals(188, liveEdgeReader!!.read(liveEdgePacket, 0, liveEdgePacket.size))
            liveEdgeReader.close()

            assertTrue(
                capacity.acquireRecording(
                    SOURCE,
                    RECORDING,
                    CHANNEL,
                    System.currentTimeMillis(),
                    sharedSessionAvailable = true,
                ).allowed,
            )
            val recordingOutput = ByteArrayOutputStream()
            val firstRecordedBytes = CompletableDeferred<Unit>()
            val recordJob = launch {
                manager.captureForRecording(
                    sourceId = SOURCE,
                    channelId = CHANNEL,
                    recordingId = RECORDING,
                    endEpochMillis = Long.MAX_VALUE,
                    media = prepared(),
                    output = recordingOutput,
                    onProgress = { firstRecordedBytes.complete(Unit) },
                )
            }
            withTimeout(2_000L) { firstRecordedBytes.await() }

            repeat(4) {
                val goLiveMedia = manager.goLive(SOURCE, CHANNEL)
                assertEquals(snapshotSession(manager), goLiveMedia?.liveDvrSessionId)
                assertEquals(setOf(RECORDING), manager.snapshot(SOURCE, CHANNEL)?.recordingIds)
            }

            val duplicateError = runCatching {
                manager.captureForRecording(
                    sourceId = SOURCE,
                    channelId = CHANNEL,
                    recordingId = RECORDING,
                    endEpochMillis = Long.MAX_VALUE,
                    media = prepared(),
                    output = ByteArrayOutputStream(),
                    onProgress = {},
                )
            }.exceptionOrNull()
            assertTrue(duplicateError is IOException)

            val snapshot = manager.snapshot(SOURCE, CHANNEL)
            assertNotNull(snapshot)
            assertEquals(1, snapshot!!.providerIngressOpenCount)
            // The old relinquish-playback/open-recording-transport path would produce ingress #2.
            assertEquals("recording must tee the session-owned provider ingress", 1, ingress.openCount.get())
            assertEquals(setOf(RECORDING), snapshot.recordingIds)
            assertTrue(snapshot.retainedBytes > 0L)
            assertTrue(snapshot.timeline.isNotEmpty())
            assertTrue(manager.detachPlayback(SOURCE, CHANNEL))
            assertFalse(manager.snapshot(SOURCE, CHANNEL)!!.playbackAttached)
            assertTrue(manager.hasSession(SOURCE, CHANNEL))

            recordJob.cancelAndJoin()
            manager.completeRecording(RECORDING)

            assertFalse(manager.hasSession(SOURCE, CHANNEL))
            assertEquals(0, capacity.activeLeaseCount(SOURCE))
            assertTrue(recordingOutput.size() > 0)
            assertEquals(1, ingress.openCount.get())
        } finally {
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun retainedTimeSeekMapsToSafeHlsSegmentAndRejectsOutsideTheRetainedWindow() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-seek-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val ingress = FakeHlsIngress()
        val manager = manager(directory, ingress, capacity)
        try {
            assertTrue(capacity.acquirePlayback(SOURCE, CHANNEL).allowed)
            val attached = manager.attachPlayback(SOURCE, CHANNEL, preparedHls())
            assertNotNull(attached)
            withTimeout(2_000L) { ingress.firstSegmentWritten.await() }
            withTimeout(2_000L) {
                while ((manager.snapshot(SOURCE, CHANNEL)?.timeline?.size ?: 0) < 4) delay(5L)
            }

            val sessionBefore = manager.snapshot(SOURCE, CHANNEL)!!
            val targetEpochMillis = BASE_EPOCH_MS + 15_000L
            val expected = sessionBefore.timeline[1]
            val sought = manager.seekPlayback(SOURCE, CHANNEL, targetEpochMillis)

            assertNotNull(sought)
            assertEquals(sessionBefore.sessionId, sought?.liveDvrSessionId)
            assertEquals(expected.startEpochMillis, sought?.liveDvrStartEpochMillis)
            assertEquals(expected.startOffset, queryOffset(sought!!.uri))
            assertNull(manager.seekPlayback(SOURCE, CHANNEL, BASE_EPOCH_MS - 1L))
            assertNull(manager.seekPlayback(SOURCE, CHANNEL, BASE_EPOCH_MS + 40_001L))
            assertEquals(1, manager.snapshot(SOURCE, CHANNEL)?.providerIngressOpenCount)
            assertEquals(1, ingress.openCount.get())
        } finally {
            manager.detachPlayback(SOURCE, CHANNEL)
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun storageHeadroomStopsCaptureWithTypedFailureAndLeavesRecordingBytesRecoverable() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-storage-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val ingress = FakeIngress()
        val storageSpace = AtomicReference(
            LiveDvrStorageSpace(usableBytes = 2L * 1024L * 1024L * 1024L, totalBytes = 16L * 1024L * 1024L * 1024L),
        )
        val manager = manager(
            directory = directory,
            ingress = ingress,
            capacity = capacity,
            storageMonitor = LiveDvrStorageMonitor { storageSpace.get() },
        )
        val recordingOutput = ByteArrayOutputStream()
        try {
            assertTrue(capacity.acquireRecording(SOURCE, RECORDING, CHANNEL, 1L).allowed)
            val capture = async {
                runCatching {
                    manager.captureForRecording(
                        sourceId = SOURCE,
                        channelId = CHANNEL,
                        recordingId = RECORDING,
                        endEpochMillis = Long.MAX_VALUE,
                        media = prepared(),
                        output = recordingOutput,
                        onProgress = {},
                    )
                }.exceptionOrNull()
            }
            withTimeout(2_000L) {
                while (recordingOutput.size() < 188 * 64) delay(5L)
            }
            storageSpace.set(
                LiveDvrStorageSpace(usableBytes = LiveDvrStoragePolicy.MIN_HEADROOM_BYTES, totalBytes = 16L * 1024L * 1024L * 1024L),
            )

            val failure = withTimeout(2_000L) { capture.await() }
            assertTrue(failure is LiveRecordingCaptureFailureException)
            assertEquals(LiveRecordingFailureCategory.STORAGE, (failure as LiveRecordingCaptureFailureException).category)
            assertTrue(recordingOutput.size() >= 188 * 64)
            assertEquals(LiveRecordingFailureCategory.STORAGE, manager.snapshot(SOURCE, CHANNEL)?.failureCategory)
            assertTrue(manager.completeRecording(RECORDING))
            assertTrue(recordingOutput.size() >= 188 * 64)
        } finally {
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun scheduledRecordingSessionIsReusedWhenPlaybackAttaches() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-scheduled-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val ingress = FakeIngress()
        val manager = manager(directory, ingress, capacity)
        try {
            assertTrue(capacity.acquireRecording(SOURCE, RECORDING, CHANNEL, System.currentTimeMillis()).allowed)
            val recordingOutput = ByteArrayOutputStream()
            val recordJob = launch {
                manager.captureForRecording(
                    sourceId = SOURCE,
                    channelId = CHANNEL,
                    recordingId = RECORDING,
                    endEpochMillis = Long.MAX_VALUE,
                    media = prepared(),
                    output = recordingOutput,
                    onProgress = {},
                )
            }
            withTimeout(2_000L) { ingress.started.await() }

            val playbackMedia = manager.attachPlayback(SOURCE, CHANNEL, prepared())
            assertNotNull(playbackMedia)
            assertTrue(playbackMedia!!.uri.startsWith("${LiveDvrSessionManager.DVR_SCHEME}://"))
            assertEquals(1, manager.snapshot(SOURCE, CHANNEL)!!.providerIngressOpenCount)
            assertEquals(1, ingress.openCount.get())

            recordJob.cancelAndJoin()
            assertTrue(manager.completeRecording(RECORDING))
            assertEquals(1, capacity.activeLeaseCount(SOURCE))
            manager.detachPlayback(SOURCE, CHANNEL)
            assertEquals(0, capacity.activeLeaseCount(SOURCE))
        } finally {
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupFailureFallsBackSequentiallyWithoutOpeningParallelProviderIngress() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-startup-fallback-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val attempts = mutableListOf<Pair<String, Boolean>>()
        val fallbackStarted = CompletableDeferred<Unit>()
        val ingress = object : LiveDvrIngressClient {
            override suspend fun capture(
                uri: String,
                isHls: Boolean,
                startEpochMillis: Long,
                endEpochMillis: Long,
                output: OutputStream,
                onProgress: suspend (Long) -> Unit,
            ) {
                attempts += uri to isHls
                if (attempts.size == 1) {
                    throw LiveRecordingCaptureFailureException(
                        LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
                        "primary transport unavailable",
                    )
                }
                fallbackStarted.complete(Unit)
                val packet = ByteArray(188) { index -> if (index == 0) 0x47 else index.toByte() }
                var total = 0L
                while (true) {
                    output.write(packet)
                    total += packet.size
                    onProgress(total)
                    delay(5L)
                }
            }
        }
        val manager = manager(directory, ingress, capacity)
        val media = PreparedPlaybackMedia(
            uri = "https://provider.invalid/live.ts",
            mimeType = LiveDvrSessionManager.MPEG_TS_MIME_TYPE,
            fallback = PreparedPlaybackAlternative(
                uri = "https://provider.invalid/live.m3u8",
                mimeType = "application/vnd.apple.mpegurl",
            ),
            usesProviderConnection = true,
        )
        try {
            assertTrue(capacity.acquirePlayback(SOURCE, CHANNEL).allowed)
            assertNotNull(manager.attachPlayback(SOURCE, CHANNEL, media))
            withTimeout(2_000L) { fallbackStarted.await() }
            withTimeout(2_000L) {
                while ((manager.snapshot(SOURCE, CHANNEL)?.retainedBytes ?: 0L) == 0L) delay(5L)
            }

            assertEquals(
                listOf(
                    "https://provider.invalid/live.ts" to false,
                    "https://provider.invalid/live.m3u8" to true,
                ),
                attempts.take(2),
            )
            assertEquals(2, manager.snapshot(SOURCE, CHANNEL)?.providerIngressOpenCount)
            assertTrue(manager.hasSession(SOURCE, CHANNEL))
        } finally {
            manager.detachPlayback(SOURCE, CHANNEL)
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun reconnectAdvancesGenerationAndRejectsWritesFromTheOldIngress() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-reconnect-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val openCount = AtomicInteger()
        val oldOutput = AtomicReference<OutputStream?>()
        val secondAttempt = CompletableDeferred<Unit>()
        val ingress = object : LiveDvrIngressClient {
            override suspend fun capture(
                uri: String,
                isHls: Boolean,
                startEpochMillis: Long,
                endEpochMillis: Long,
                output: OutputStream,
                onProgress: suspend (Long) -> Unit,
            ) {
                val attempt = openCount.incrementAndGet()
                val packet = ByteArray(188) { index -> if (index == 0) 0x47 else index.toByte() }
                if (attempt == 1) {
                    oldOutput.set(output)
                    output.write(packet)
                    onProgress(packet.size.toLong())
                    throw IOException("simulated reconnect")
                }
                secondAttempt.complete(Unit)
                var total = 0L
                while (true) {
                    output.write(packet)
                    total += packet.size
                    onProgress(total)
                    delay(5L)
                }
            }
        }
        val manager = manager(directory, ingress, capacity)
        try {
            assertTrue(capacity.acquirePlayback(SOURCE, CHANNEL).allowed)
            manager.attachPlayback(SOURCE, CHANNEL, prepared())
            withTimeout(3_000L) { secondAttempt.await() }

            val snapshot = manager.snapshot(SOURCE, CHANNEL)
            assertNotNull(snapshot)
            assertEquals(2, snapshot!!.providerIngressOpenCount)
            assertEquals(2L, snapshot.generation)
            val staleWriteError = runCatching {
                oldOutput.get()!!.write(ByteArray(188) { 0x47.toByte() })
            }.exceptionOrNull()
            assertTrue(staleWriteError is IOException)
        } finally {
            manager.detachPlayback(SOURCE, CHANNEL)
            manager.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun stopAndFinalizationDuringReconnectBackoffCannotOpenAStaleProviderGeneration() = runBlocking {
        val directory = Files.createTempDirectory("ownplay-dvr-reconnect-stop-test").toFile()
        val capacity = LiveCapacityCoordinator(Metadata())
        val openCount = AtomicInteger()
        val firstFailure = CompletableDeferred<Unit>()
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val recordingAttached = CompletableDeferred<Unit>()
        val ingress = object : LiveDvrIngressClient {
            override suspend fun capture(
                uri: String,
                isHls: Boolean,
                startEpochMillis: Long,
                endEpochMillis: Long,
                output: OutputStream,
                onProgress: suspend (Long) -> Unit,
            ) {
                val attempt = openCount.incrementAndGet()
                val packet = ByteArray(188) { index -> if (index == 0) 0x47 else 0 }
                if (attempt == 1) {
                    firstAttemptStarted.complete(Unit)
                    recordingAttached.await()
                }
                output.write(packet)
                onProgress(packet.size.toLong())
                if (attempt == 1) {
                    firstFailure.complete(Unit)
                    throw IOException("simulated transport loss")
                }
                while (true) {
                    output.write(packet)
                    onProgress(packet.size.toLong())
                    delay(5L)
                }
            }
        }
        val manager = manager(directory, ingress, capacity)
        val recordingOutput = ByteArrayOutputStream()
        val recordingId = "recording-racing-reconnect"
        try {
            assertTrue(capacity.acquirePlayback(SOURCE, CHANNEL).allowed)
            assertNotNull(manager.attachPlayback(SOURCE, CHANNEL, prepared()))
            withTimeout(2_000L) { firstAttemptStarted.await() }
            assertTrue(capacity.acquireRecording(SOURCE, recordingId, CHANNEL, 1L, sharedSessionAvailable = true).allowed)
            val recording = launch {
                manager.captureForRecording(
                    sourceId = SOURCE,
                    channelId = CHANNEL,
                    recordingId = recordingId,
                    endEpochMillis = Long.MAX_VALUE,
                    media = prepared(),
                    output = recordingOutput,
                    onProgress = {},
                )
            }
            withTimeout(2_000L) {
                while (recordingId !in manager.snapshot(SOURCE, CHANNEL)?.recordingIds.orEmpty()) delay(1L)
            }
            recordingAttached.complete(Unit)
            withTimeout(2_000L) { firstFailure.await() }
            manager.detachPlayback(SOURCE, CHANNEL)
            recording.cancelAndJoin()
            assertTrue(manager.completeRecording(recordingId))
            delay(350L)

            assertEquals(1, openCount.get())
            assertNull(manager.snapshot(SOURCE, CHANNEL))
            assertEquals(0, capacity.activeLeaseCount(SOURCE))
            assertTrue(recordingOutput.size() >= 188)
        } finally {
            manager.close()
            directory.deleteRecursively()
        }
    }

    private fun manager(
        directory: File,
        ingress: LiveDvrIngressClient,
        capacity: LiveCapacityCoordinator,
        storageMonitor: LiveDvrStorageMonitor = LiveDvrStorageMonitor {
            LiveDvrStorageSpace(usableBytes = 2L * 1024L * 1024L * 1024L, totalBytes = 16L * 1024L * 1024L * 1024L)
        },
    ) = LiveDvrSessionManager(
        sessionDirectory = directory,
        sourceResolver = FakeResolver(),
        mediaPreparer = FakePreparer(),
        ingressClient = ingress,
        capacityCoordinator = capacity,
        storageMonitor = storageMonitor,
    )

    private fun prepared() = PreparedPlaybackMedia(
        uri = "https://provider.invalid/live.ts",
        mimeType = LiveDvrSessionManager.MPEG_TS_MIME_TYPE,
        usesProviderConnection = true,
    )

    private fun preparedHls() = PreparedPlaybackMedia(
        uri = "https://provider.invalid/live.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        usesProviderConnection = true,
    )

    private fun queryOffset(uri: String): Long = uri.substringAfter('?')
        .split('&')
        .first { it.startsWith("offset=") }
        .substringAfter('=').toLong()

    private fun snapshotSession(manager: LiveDvrSessionManager): String? =
        manager.snapshot(SOURCE, CHANNEL)?.sessionId

    private companion object {
        const val SOURCE = "source"
        const val CHANNEL = "channel"
        const val RECORDING = "recording"
        const val BASE_EPOCH_MS = 1_700_000_000_000L
    }
}
