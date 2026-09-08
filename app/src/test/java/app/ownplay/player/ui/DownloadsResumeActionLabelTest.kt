package app.ownplay.player.ui

import app.ownplay.player.download.OfflinePlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsResumeActionLabelTest {
    @Test
    fun incompleteUsableOfflineProgressUsesResumeOffline() {
        val progress = OfflinePlaybackProgress(
            positionMs = 5_001L,
            durationMs = 60_000L,
            completed = false,
        )

        assertTrue(offlineResumeAvailable(progress))
        assertEquals("Resume Offline", completedOfflineActionLabel(resumeAvailable = true))
    }

    @Test
    fun completedOfflineProgressUsesPlayOffline() {
        val progress = OfflinePlaybackProgress(
            positionMs = 59_000L,
            durationMs = 60_000L,
            completed = true,
        )

        assertFalse(offlineResumeAvailable(progress))
        assertEquals("Play Offline", completedOfflineActionLabel(resumeAvailable = false))
    }

    @Test
    fun nearStartProgressDoesNotAdvertiseResumeThatPlaybackHostWillNotApply() {
        val progress = OfflinePlaybackProgress(
            positionMs = 5_000L,
            durationMs = 60_000L,
            completed = false,
        )

        assertFalse(offlineResumeAvailable(progress))
        assertEquals("Play Offline", completedOfflineActionLabel(resumeAvailable = false))
    }
}
