package app.ownplay.mobile.feature.playback.data

import android.content.Context
import android.net.Uri
import android.test.mock.MockContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
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
    fun factoryRoutesLiveDvrUriThroughTheMedia3DataSourcePath() {
        val expected = ByteArray(188 * 2) { index ->
            if (index % 188 == 0) 0x47 else 0
        }
        var liveOpenCount = 0
        val provider = object : LiveDvrDataSourceProvider {
            override fun openReadHandle(sessionId: String, position: Long): LiveDvrReadHandle? =
                TestReadHandle(expected, position)

            override fun openLiveReadHandle(sessionId: String): LiveDvrReadHandle? {
                liveOpenCount += 1
                return TestReadHandle(expected, 0L)
            }
        }
        val dataSource = LiveDvrDataSourceFactory(TestContext(), provider).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("ownplaydvr://session-id/live.ts?start=live"))
            .build()

        val length = dataSource.open(spec)
        val actual = ByteArray(expected.size)
        val read = dataSource.read(actual, 0, actual.size)
        dataSource.close()

        assertEquals(1, liveOpenCount)
        assertEquals(expected.size.toLong(), length)
        assertEquals(expected.size, read)
        assertTrue(expected.contentEquals(actual))
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
}
