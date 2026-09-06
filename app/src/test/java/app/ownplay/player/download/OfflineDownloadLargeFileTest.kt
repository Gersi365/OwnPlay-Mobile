package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDownloadLargeFileTest {
    @Test
    fun progressFractionHandlesTwelveGigabyteDownloadWithoutOverflow() {
        val twelveGiB = 12L * 1024L * 1024L * 1024L
        val sixGiB = 6L * 1024L * 1024L * 1024L
        val download = sampleDownload(
            bytesDownloaded = sixGiB,
            totalBytes = twelveGiB,
        )

        assertEquals(0.5f, download.progressFraction ?: error("Expected known progress"), 0.0001f)
    }

    @Test
    fun progressFractionClampsWhenReportedBytesExceedTotal() {
        val twelveGiB = 12L * 1024L * 1024L * 1024L
        val download = sampleDownload(
            bytesDownloaded = twelveGiB + 1024L,
            totalBytes = twelveGiB,
        )

        assertEquals(1.0f, download.progressFraction ?: error("Expected known progress"), 0.0001f)
    }

    @Test
    fun progressFractionStaysIndeterminateWhenProviderDoesNotReportLength() {
        val download = sampleDownload(
            bytesDownloaded = 3L * 1024L * 1024L * 1024L,
            totalBytes = null,
        )

        assertNull(download.progressFraction)
    }

    private fun sampleDownload(
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): OfflineDownload = OfflineDownload(
        downloadId = "download",
        sourceId = "source",
        mediaKind = "movie",
        contentId = "movie",
        title = "Large movie",
        seriesTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        posterUrl = null,
        state = "downloading",
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        failureReason = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )
}
