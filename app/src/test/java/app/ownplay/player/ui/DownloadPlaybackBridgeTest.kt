package app.ownplay.player.ui

import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlaybackBridgeTest {
    @Test
    fun staleOwnerCannotClearNewerDownloadPlaybackAction() {
        val firstOwner = Any()
        val secondOwner = Any()
        var playedDownloadId: String? = null

        DownloadPlaybackBridge.register(firstOwner) { playedDownloadId = "first" }
        DownloadPlaybackBridge.register(secondOwner) { download ->
            playedDownloadId = download.downloadId
        }

        DownloadPlaybackBridge.clear(firstOwner)

        assertTrue(DownloadPlaybackBridge.request(sampleDownload("download-2")))
        assertEquals("download-2", playedDownloadId)

        DownloadPlaybackBridge.clear(secondOwner)
        assertFalse(DownloadPlaybackBridge.request(sampleDownload("download-3")))
    }

    @Test
    fun playbackCloseNotifiesOnlyWhileOwnerIsRegistered() {
        val owner = Any()
        var closedDownloadId: String? = null
        try {
            DownloadPlaybackBridge.registerPlaybackClosed(owner) { downloadId ->
                closedDownloadId = downloadId
            }

            DownloadPlaybackBridge.notifyPlaybackClosed("download-1")
            assertEquals("download-1", closedDownloadId)

            DownloadPlaybackBridge.clearPlaybackClosed(owner)
            DownloadPlaybackBridge.notifyPlaybackClosed("download-2")
            assertEquals("download-1", closedDownloadId)
        } finally {
            DownloadPlaybackBridge.clearPlaybackClosed(owner)
        }
    }

    private fun sampleDownload(downloadId: String): OfflineDownload = OfflineDownload(
        downloadId = downloadId,
        sourceId = "source",
        mediaKind = DownloadMediaKinds.MOVIE,
        contentId = "movie",
        title = "Movie",
        seriesTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        posterUrl = null,
        state = DownloadStates.COMPLETED,
        bytesDownloaded = 100L,
        totalBytes = 100L,
        failureReason = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )
}
