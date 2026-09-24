package app.ownplay.mobile.downloads.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedMediaVerifierTest {
    private val bytes = ByteArray(150_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun verifiesMultipleBuffersAndRejectsSameSizeCorruption() = runBlocking {
        assertTrue(verifyDownloadedBytes(bytes.inputStream(), bytes.size.toLong(), hash.uppercase()))
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = 99 }
        assertFalse(verifyDownloadedBytes(corrupt.inputStream(), bytes.size.toLong(), hash))
    }

    @Test
    fun rejectsTruncatedExtendedEmptyAndMalformedIntegrity() = runBlocking {
        assertFalse(verifyDownloadedBytes(bytes.inputStream(), bytes.size + 1L, hash))
        assertFalse(verifyDownloadedBytes(bytes.inputStream(), bytes.size - 1L, hash))
        assertFalse(verifyDownloadedBytes(byteArrayOf().inputStream(), 1L, null))
        assertFalse(verifyDownloadedBytes(bytes.inputStream(), 0L, null))
        assertFalse(verifyDownloadedBytes(bytes.inputStream(), bytes.size.toLong(), "invalid"))
    }

    @Test
    fun historicalSizeOnlyMetadataStillRequiresExactByteCount() = runBlocking {
        assertTrue(verifyDownloadedBytes(bytes.inputStream(), bytes.size.toLong(), null))
        assertFalse(verifyDownloadedBytes(bytes.inputStream(), bytes.size + 1L, null))
    }

    @Test(expected = IOException::class)
    fun readFailureCannotBeReportedAsVerified() = runBlocking {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
        }
        verifyDownloadedBytes(input, 4L, null)
        Unit
    }

    @Test
    fun cancellationStopsVerificationBetweenReads() = runBlocking {
        val verificationJob = Job()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val read = super.read(buffer, offset, length)
                verificationJob.cancel()
                return read
            }
        }
        var cancelled = false
        try {
            withContext(verificationJob) { verifyDownloadedBytes(input, bytes.size.toLong(), hash) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(input.available() > 0)
    }
}
