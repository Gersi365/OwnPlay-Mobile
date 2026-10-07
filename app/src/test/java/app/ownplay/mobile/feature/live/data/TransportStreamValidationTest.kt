package app.ownplay.mobile.feature.live.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TransportStreamValidationTest {
    private fun packets(count: Int) = ByteArray(188 * count).apply {
        for (index in 0 until count) this[index * 188] = 0x47
    }

    @Test fun firstMediaRequiresValidatedPacketsAndReportsOnlyOnce() {
        var reports = 0
        val output = TransportStreamValidatingOutputStream(ByteArrayOutputStream()) { reports++ }
        output.write(packets(63))
        assertEquals(0, reports)
        output.write(packets(1))
        output.ensureValid()
        assertEquals(1, reports)
        output.write(packets(10))
        assertEquals(1, reports)
    }

    @Test fun corruptionTruncationAndTooShortOutputsCannotPublish() {
        for (bytes in listOf(packets(63), packets(64).dropLast(1).toByteArray(), packets(64).apply { this[188] = 0 })) {
            val output = TransportStreamValidatingOutputStream(ByteArrayOutputStream())
            output.write(bytes)
            assertThrows(IOException::class.java) { output.ensureValid() }
        }
        var reports = 0
        val output = TransportStreamValidatingOutputStream(ByteArrayOutputStream()) { reports++ }
        output.write(packets(64).apply { this[0] = 0 })
        assertEquals(0, reports)
    }

    @Test fun splitWritesValidateEveryPacketBoundary() {
        val bytes = packets(80)
        val output = TransportStreamValidatingOutputStream(ByteArrayOutputStream())
        bytes.asList().chunked(173).forEach { output.write(it.toByteArray()) }
        output.ensureValid()
        assertEquals(bytes.size.toLong(), output.bytesWritten)
    }
}
