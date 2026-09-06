package app.ownplay.player.ui.library

import app.ownplay.player.persistence.download.DownloadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOfflinePresentationTest {
    @Test
    fun `completed public download is explicit offline local playback`() {
        val presentation = libraryOfflinePresentation(
            state = DownloadStates.COMPLETED,
            savedToDownloads = true,
        )

        assertTrue(presentation.verifiedOffline)
        assertEquals("OFFLINE", presentation.badgeLabel)
        assertEquals("Play Offline", presentation.actionLabel)
        assertEquals("Local file · Phone Downloads", presentation.storageLabel)
    }

    @Test
    fun `completed private download is explicit offline local playback`() {
        val presentation = libraryOfflinePresentation(
            state = DownloadStates.COMPLETED,
            savedToDownloads = false,
        )

        assertTrue(presentation.verifiedOffline)
        assertEquals("Local file · OwnPlay private storage", presentation.storageLabel)
    }

    @Test
    fun `incomplete or failed download never claims offline availability`() {
        listOf(
            DownloadStates.QUEUED,
            DownloadStates.DOWNLOADING,
            DownloadStates.PAUSED,
            DownloadStates.FAILED,
        ).forEach { state ->
            val presentation = libraryOfflinePresentation(
                state = state,
                savedToDownloads = false,
            )

            assertFalse(presentation.verifiedOffline)
            assertNull(presentation.badgeLabel)
            assertNull(presentation.actionLabel)
            assertNull(presentation.storageLabel)
        }
    }

    @Test
    fun `series offline label distinguishes local episode copies`() {
        assertNull(librarySeriesOfflineLabel(0))
        assertEquals("OFFLINE · 1 episode local", librarySeriesOfflineLabel(1))
        assertEquals("OFFLINE · 3 episodes local", librarySeriesOfflineLabel(3))
    }
}
