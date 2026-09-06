package app.ownplay.player.ui.vod

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePosterPolicyTest {
    @Test
    fun `poster sample size keeps small images unchanged`() {
        assertEquals(1, calculatePosterInSampleSize(width = 512, height = 700, maxLongEdgePx = 768))
    }

    @Test
    fun `poster sample size downsamples large provider artwork`() {
        assertEquals(8, calculatePosterInSampleSize(width = 4_000, height = 6_000, maxLongEdgePx = 768))
        assertEquals(2, calculatePosterInSampleSize(width = 1_200, height = 800, maxLongEdgePx = 768))
    }

    @Test
    fun `bounded poster read accepts payload at limit`() {
        val payload = ByteArray(32) { index -> index.toByte() }
        val result = readPosterBytes(ByteArrayInputStream(payload), maxBytes = payload.size)

        assertArrayEquals(payload, result)
    }

    @Test
    fun `bounded poster read rejects payload over limit`() {
        val payload = ByteArray(33) { index -> index.toByte() }

        assertNull(readPosterBytes(ByteArrayInputStream(payload), maxBytes = 32))
    }
}
