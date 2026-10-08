package app.ownplay.mobile.feature.playback.data

import android.content.Context
import android.net.Uri
import android.test.mock.MockContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import app.ownplay.mobile.feature.playback.domain.LiveDvrDataSourceProvider
import app.ownplay.mobile.feature.playback.domain.LiveDvrReadHandle
import app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LiveDvrDataSourceFactoryTest {
    private class TestContext : MockContext() {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun retainedTransportStreamFlowsThroughTheProductionMedia3ExtractorPath() {
        val retainedPackets = syntheticNullPidTransportStream(packetCount = 5)
        var liveOpenCount = 0
        val provider = object : LiveDvrDataSourceProvider {
            override fun openReadHandle(sessionId: String, position: Long): LiveDvrReadHandle? =
                TestReadHandle(retainedPackets, position)

            override fun openLiveReadHandle(sessionId: String): LiveDvrReadHandle? {
                liveOpenCount += 1
                return TestReadHandle(retainedPackets, 0L)
            }
        }
        val dataSource = LiveDvrDataSourceFactory(TestContext(), provider).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("ownplaydvr://session-id/live.ts?start=live"))
            .build()

        try {
            val length = dataSource.open(spec)
            val input = DefaultExtractorInput(dataSource, 0L, length)
            val extractor = DefaultExtractorsFactory()
                .createExtractors()
                .filterIsInstance<TsExtractor>()
                .single()

            assertEquals(1, liveOpenCount)
            assertEquals(retainedPackets.size.toLong(), length)
            assertTrue("Media3 must sniff the retained TS packets.", extractor.sniff(input))

            extractor.init(NullPidExtractorOutput)
            val positionHolder = PositionHolder()
            var result = Extractor.RESULT_CONTINUE
            var readCalls = 0
            while (result == Extractor.RESULT_CONTINUE && readCalls < MAX_EXTRACTOR_READ_CALLS) {
                result = extractor.read(input, positionHolder)
                readCalls += 1
            }

            assertEquals(Extractor.RESULT_END_OF_INPUT, result)
            assertEquals(retainedPackets.size.toLong(), input.position)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun invalidLiveDvrUriCarriesASafeTypedFailureStage() {
        val provider = object : LiveDvrDataSourceProvider {
            override fun openReadHandle(sessionId: String, position: Long): LiveDvrReadHandle? = null
            override fun openLiveReadHandle(sessionId: String): LiveDvrReadHandle? = null
        }
        val dataSource = LiveDvrDataSourceFactory(TestContext(), provider).createDataSource()
        val error = runCatching {
            dataSource.open(
                DataSpec.Builder()
                    .setUri(Uri.parse("ownplaydvr:///live.ts?start=live"))
                    .build(),
            )
        }.exceptionOrNull()

        assertTrue(error is LiveDvrDataSourceFailureException)
        assertEquals(PlaybackFailureStage.LIVE_DVR_URI, (error as LiveDvrDataSourceFailureException).stage)
    }

    private fun syntheticNullPidTransportStream(packetCount: Int): ByteArray =
        ByteArray(TS_PACKET_BYTES * packetCount) { 0xFF.toByte() }.apply {
            repeat(packetCount) { packet ->
                val start = packet * TS_PACKET_BYTES
                this[start] = TS_SYNC_BYTE.toByte()
                this[start + 1] = 0x1F
                this[start + 2] = 0xFF.toByte()
                this[start + 3] = 0x10
            }
        }

    private object NullPidExtractorOutput : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput =
            throw AssertionError("Null-PID fixture must not declare tracks.")

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class TestReadHandle(
        private val bytes: ByteArray,
        private var position: Long,
    ) : LiveDvrReadHandle {
        override val availableLength: Long = bytes.size.toLong()
        override val ended: Boolean = true
        override val startEpochMillis: Long? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            position += count
            return count
        }

        override fun close() = Unit
    }

    private companion object {
        const val TS_PACKET_BYTES = 188
        const val TS_SYNC_BYTE = 0x47
        const val MAX_EXTRACTOR_READ_CALLS = 8
    }
}
