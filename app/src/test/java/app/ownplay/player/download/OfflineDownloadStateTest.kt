package app.ownplay.player.download

import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadStateTest {
    @Test
    fun pausedDownloadKeepsProgressButIsNotActive() {
        val download = sampleDownload(
            state = DownloadStates.PAUSED,
            bytesDownloaded = 3L * GIB,
            totalBytes = 12L * GIB,
        )

        assertTrue(download.paused)
        assertFalse(download.active)
        assertFalse(download.completed)
        assertEquals(0.25f, download.progressFraction ?: error("Expected known progress"), 0.0001f)
    }

    @Test
    fun queuedAndDownloadingStatesRemainActive() {
        val queued = sampleDownload(state = DownloadStates.QUEUED)
        val downloading = sampleDownload(state = DownloadStates.DOWNLOADING)

        assertTrue(queued.active)
        assertFalse(queued.paused)
        assertTrue(downloading.active)
        assertFalse(downloading.paused)
    }

    @Test
    fun completedDownloadIsNeitherActiveNorPaused() {
        val download = sampleDownload(state = DownloadStates.COMPLETED)

        assertTrue(download.completed)
        assertFalse(download.active)
        assertFalse(download.paused)
    }

    private fun sampleDownload(
        state: String,
        bytesDownloaded: Long = 0L,
        totalBytes: Long? = null,
    ): OfflineDownload = OfflineDownload(
        downloadId = "download",
        sourceId = "source",
        mediaKind = DownloadMediaKinds.MOVIE,
        contentId = "movie",
        title = "Movie",
        seriesTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        posterUrl = null,
        state = state,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        failureReason = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
