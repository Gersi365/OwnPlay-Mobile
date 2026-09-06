package app.ownplay.player.ui.library

import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryPlaybackPresentationSessionTest {
    @Test
    fun processSessionRetainsPresentationUntilExplicitClear() {
        LibraryPlaybackPresentationSession.clear()
        val session = LibraryPlaybackSession(
            download = OfflineDownload(
                downloadId = "download-1",
                sourceId = "source-1",
                mediaKind = DownloadMediaKinds.MOVIE,
                contentId = "movie-1",
                title = "Movie",
                seriesTitle = null,
                seasonNumber = null,
                episodeNumber = null,
                posterUrl = null,
                state = DownloadStates.COMPLETED,
                bytesDownloaded = 1_024L,
                totalBytes = 1_024L,
                failureReason = null,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            ),
            initialPositionMs = 12_345L,
        )

        try {
            LibraryPlaybackPresentationSession.show(session)

            assertSame(session, LibraryPlaybackPresentationSession.state.value)
        } finally {
            LibraryPlaybackPresentationSession.clear()
        }

        assertNull(LibraryPlaybackPresentationSession.state.value)
    }
}
