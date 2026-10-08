package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.feature.live.data.LiveDvrIngressSegment
import app.ownplay.mobile.feature.live.data.LiveDvrSegmentBoundaryOutput
import app.ownplay.mobile.feature.live.data.LiveRecordingCaptureClient
import app.ownplay.mobile.sources.domain.SourceId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.time.Instant
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LiveReconnectLocalHttpFixtureTest {
    @Test
    fun redirectedHlsPlaylistResolvesRelativeSegmentsFromEffectiveResponseUri() = kotlinx.coroutines.runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestedSegmentPath = AtomicReference<String?>()
        val segmentBytes = ByteArray(188) { index -> if (index == 0) 0x47 else 0 }
        server.createContext("/redirect/master.m3u8") { exchange ->
            exchange.responseHeaders.add("Location", "/effective/path/index.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/effective/path/index.m3u8") { exchange ->
            val playlist = (
                "#EXTM3U\n" +
                    "#EXT-X-VERSION:3\n" +
                    "#EXT-X-MEDIA-SEQUENCE:1\n" +
                    "#EXT-X-PROGRAM-DATE-TIME:${Instant.now().minusSeconds(20)}\n" +
                    "#EXTINF:1.0,\n" +
                    "segments/one.ts\n" +
                    "#EXT-X-ENDLIST\n"
                ).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, playlist.size.toLong())
            exchange.responseBody.use { it.write(playlist) }
        }
        server.createContext("/effective/path/segments/one.ts") { exchange ->
            requestedSegmentPath.set(exchange.requestURI.path)
            exchange.responseHeaders.add("Content-Type", "video/mp2t")
            exchange.sendResponseHeaders(200, segmentBytes.size.toLong())
            exchange.responseBody.use { it.write(segmentBytes) }
        }
        server.start()

        val staged = ByteArrayOutputStream()
        val committed = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val output = object : OutputStream(), LiveDvrSegmentBoundaryOutput {
            override fun write(value: Int) {
                staged.write(value)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                staged.write(buffer, offset, length)
            }

            override fun beginSegment(segment: LiveDvrIngressSegment): Boolean {
                staged.reset()
                return true
            }

            override fun endSegment(complete: Boolean) {
                if (complete) committed.write(staged.toByteArray())
                staged.reset()
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val now = System.currentTimeMillis()
            LiveRecordingCaptureClient().capture(
                uri = "$baseUrl/redirect/master.m3u8",
                isHls = true,
                startEpochMillis = now - 60_000L,
                endEpochMillis = now + 60_000L,
                output = output,
                onProgress = { progress += it },
            )
            assertEquals("/effective/path/segments/one.ts", requestedSegmentPath.get())
            org.junit.Assert.assertArrayEquals(segmentBytes, committed.toByteArray())
            assertEquals(listOf(segmentBytes.size.toLong()), progress)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun liveHlsFixtureServesThreePlaylistAndSegmentRefreshCycles() {
        LocalLiveFixture().use { fixture ->
            repeat(3) {
                val playlist = URL(fixture.playlistUrl).readText()
                assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:"))
                val segment = playlist.lineSequence().last { line ->
                    line.isNotBlank() && !line.startsWith("#")
                }
                URL("${fixture.baseUrl}/$segment").openStream().use { it.readBytes() }
            }

            assertEquals(3, fixture.playlistRequests.get())
            assertEquals(3, fixture.segmentRequests.get())
        }
    }

    @Test
    fun reconnectClosesFailedLocalHttpTransportBeforeOpeningTheNextOne() {
        LocalLiveFixture().use { fixture ->
            val engine = LocalHttpPlaybackEngine()
            val resolver = object : LivePlaybackSourceResolver {
                override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource =
                    LivePlaybackSource.Direct(fixture.streamUrl)
            }
            val preparer = object : LivePlaybackMediaPreparer {
                override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia =
                    PreparedPlaybackMedia((source as LivePlaybackSource.Direct).streamLocator)
            }
            val controller = PlaybackSessionController(
                sourceResolver = resolver,
                mediaPreparer = preparer,
                playbackEngine = engine,
                reconnectDispatcher = Dispatchers.Unconfined,
                reconnectDelay = {
                    check(fixture.awaitNoActiveStream(2, TimeUnit.SECONDS))
                },
            )
            val target = PlaybackTarget.LiveChannel(SourceId("local-fixture"), "live-channel")

            try {
                kotlinx.coroutines.runBlocking {
                    controller.activateLiveChannel(target)
                }
                assertTrue(fixture.awaitStreamRequests(1, 2, TimeUnit.SECONDS))

                engine.emitRetryableNetworkFailure()

                assertTrue(fixture.awaitStreamRequests(2, 2, TimeUnit.SECONDS))
                assertEquals(1, fixture.maxConcurrentStreams.get())
                assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)
            } finally {
                controller.clear()
                engine.release()
                fixture.awaitNoActiveStream(2, TimeUnit.SECONDS)
            }
        }
    }

    private class LocalLiveFixture : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val streamLock = Object()
        private val activeStreams = AtomicInteger()
        private val streamRequests = AtomicInteger()
        private val sequence = AtomicInteger()
        val playlistRequests = AtomicInteger()
        val segmentRequests = AtomicInteger()
        val maxConcurrentStreams = AtomicInteger()

        init {
            server.executor = executor
            server.createContext("/live.m3u8") { exchange ->
                val currentSequence = sequence.incrementAndGet()
                playlistRequests.incrementAndGet()
                respond(
                    exchange,
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n" +
                        "#EXT-X-MEDIA-SEQUENCE:$currentSequence\n#EXTINF:1.0,\nsegment.ts\n",
                )
            }
            server.createContext("/segment.ts") { exchange ->
                segmentRequests.incrementAndGet()
                respond(exchange, "video/mp2t", "fixture-segment-${sequence.get()}" )
            }
            server.createContext("/stream") { exchange ->
                val nowActive = activeStreams.incrementAndGet()
                maxConcurrentStreams.updateAndGet { previous -> maxOf(previous, nowActive) }
                streamRequests.incrementAndGet()
                synchronized(streamLock) { streamLock.notifyAll() }
                try {
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    exchange.sendResponseHeaders(200, 0)
                    val body = exchange.responseBody
                    for (iteration in 0 until 200) {
                        try {
                            body.write(byteArrayOf(1))
                            body.flush()
                        } catch (_: IOException) {
                            break
                        }
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } finally {
                    runCatching { exchange.close() }
                    activeStreams.decrementAndGet()
                    synchronized(streamLock) { streamLock.notifyAll() }
                }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        val playlistUrl: String get() = "$baseUrl/live.m3u8"
        val streamUrl: String get() = "$baseUrl/stream"

        fun awaitStreamRequests(count: Int, timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            synchronized(streamLock) {
                while (streamRequests.get() < count) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return false
                    TimeUnit.NANOSECONDS.timedWait(streamLock, remaining)
                }
            }
            return true
        }

        fun awaitNoActiveStream(timeout: Long, unit: TimeUnit): Boolean {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            synchronized(streamLock) {
                while (activeStreams.get() > 0) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return false
                    TimeUnit.NANOSECONDS.timedWait(streamLock, remaining)
                }
            }
            return true
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun respond(exchange: HttpExchange, contentType: String, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private class LocalHttpPlaybackEngine : PlaybackEngine {
        private val requests = Executors.newCachedThreadPool()
        private var listener: ((PlaybackEngineEvent) -> Unit)? = null
        private var activeConnection: HttpURLConnection? = null
        private var revision = 0L

        override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
            this.listener = listener
        }

        override fun replace(media: PreparedPlaybackMedia): Long {
            clear()
            revision += 1L
            val activeRevision = revision
            val connection = URL(media.uri).openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            activeConnection = connection
            requests.execute {
                runCatching {
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(256)
                        while (stream.read(buffer) >= 0) Unit
                    }
                }
            }
            return activeRevision
        }

        override fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        override fun clear() {
            activeConnection?.disconnect()
            activeConnection = null
        }

        override fun release() {
            clear()
            requests.shutdownNow()
        }

        fun emitRetryableNetworkFailure() {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    readiness = PlaybackEngineReadiness.FAILED,
                    recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
                ),
            )
        }
    }
}
