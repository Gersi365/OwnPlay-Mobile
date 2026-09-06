package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDownloadResponsePolicyTest {
    @Test
    fun appendsOnlyWhenContentRangeStartsAtExistingBytes() {
        assertEquals(
            OfflineDownloadResponsePlan(
                disposition = OfflineDownloadWriteDisposition.APPEND,
                expectedTotalBytes = 2_000L,
            ),
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 1_000L,
                contentRange = "bytes 1000-1999/2000",
                contentLength = 1_000L,
            ),
        )
    }

    @Test
    fun mismatchedResumeRangeRestartsInsteadOfAppending() {
        assertEquals(
            OfflineDownloadWriteDisposition.RESTART,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 1_000L,
                contentRange = "bytes 500-1499/2000",
                contentLength = 1_000L,
            ).disposition,
        )
    }

    @Test
    fun missingRangeMetadataRestartsResumeButFailsFreshPartialResponse() {
        assertEquals(
            OfflineDownloadWriteDisposition.RESTART,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 1_000L,
                contentRange = null,
                contentLength = 1_000L,
            ).disposition,
        )
        assertEquals(
            OfflineDownloadWriteDisposition.FAIL,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 0L,
                contentRange = null,
                contentLength = 1_000L,
            ).disposition,
        )
    }

    @Test
    fun unknownPartialTotalIsNeverTreatedAsComplete() {
        assertEquals(
            OfflineDownloadWriteDisposition.RESTART,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 1_000L,
                contentRange = "bytes 1000-1999/*",
                contentLength = 1_000L,
            ).disposition,
        )
        assertEquals(
            OfflineDownloadWriteDisposition.FAIL,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 0L,
                contentRange = "bytes 0-999/*",
                contentLength = 1_000L,
            ).disposition,
        )
    }

    @Test
    fun rangeLengthMustMatchResponseBodyLength() {
        assertEquals(
            OfflineDownloadWriteDisposition.RESTART,
            OfflineDownloadResponsePolicy.plan(
                statusCode = 206,
                existingBytes = 1_000L,
                contentRange = "bytes 1000-1999/2000",
                contentLength = 999L,
            ).disposition,
        )
    }

    @Test
    fun fullResponseReplacesPartialContent() {
        assertEquals(
            OfflineDownloadResponsePlan(
                disposition = OfflineDownloadWriteDisposition.WRITE_FROM_ZERO,
                expectedTotalBytes = 2_000L,
            ),
            OfflineDownloadResponsePolicy.plan(
                statusCode = 200,
                existingBytes = 1_000L,
                contentRange = null,
                contentLength = 2_000L,
            ),
        )
    }

    @Test
    fun noContentAndKnownEmptyBodiesFail() {
        listOf(
            OfflineDownloadResponsePolicy.plan(204, 0L, null, null),
            OfflineDownloadResponsePolicy.plan(205, 0L, null, null),
            OfflineDownloadResponsePolicy.plan(200, 0L, null, 0L),
        ).forEach { plan ->
            assertEquals(OfflineDownloadWriteDisposition.FAIL, plan.disposition)
        }
    }
}
