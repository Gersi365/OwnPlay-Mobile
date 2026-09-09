package app.ownplay.mobile.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPoliciesTest {
    @Test
    fun `resume preserves saved position`() {
        assertEquals(
            42_000L,
            PlaybackStartPolicy.startPositionMillis(PlaybackStartMode.RESUME, 42_000L),
        )
    }

    @Test
    fun `play from beginning starts at zero`() {
        assertEquals(
            0L,
            PlaybackStartPolicy.startPositionMillis(PlaybackStartMode.FROM_BEGINNING, 42_000L),
        )
    }

    @Test
    fun `download actions follow canonical state model`() {
        assertEquals(DownloadPrimaryAction.DOWNLOAD, DownloadActionPolicy.primaryAction(null))
        assertEquals(DownloadPrimaryAction.PAUSE, DownloadActionPolicy.primaryAction(ManagedDownloadState.QUEUED))
        assertEquals(DownloadPrimaryAction.PAUSE, DownloadActionPolicy.primaryAction(ManagedDownloadState.DOWNLOADING))
        assertEquals(DownloadPrimaryAction.RESUME, DownloadActionPolicy.primaryAction(ManagedDownloadState.PAUSED))
        assertEquals(DownloadPrimaryAction.RETRY, DownloadActionPolicy.primaryAction(ManagedDownloadState.FAILED))
        assertEquals(DownloadPrimaryAction.PLAY_OFFLINE, DownloadActionPolicy.primaryAction(ManagedDownloadState.COMPLETED))
    }
}
