package app.ownplay.mobile.downloads.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionPolicyTest {
    @Test
    fun canonicalPrimaryActionFollowsDownloadState() {
        assertEquals(DownloadUserAction.DOWNLOAD, DownloadUserActionPolicy.primary(null))
        assertEquals(DownloadUserAction.PAUSE, DownloadUserActionPolicy.primary(DownloadStatus.QUEUED))
        assertEquals(DownloadUserAction.PAUSE, DownloadUserActionPolicy.primary(DownloadStatus.DOWNLOADING))
        assertEquals(DownloadUserAction.RESUME, DownloadUserActionPolicy.primary(DownloadStatus.PAUSED))
        assertEquals(DownloadUserAction.RETRY, DownloadUserActionPolicy.primary(DownloadStatus.FAILED))
        assertEquals(DownloadUserAction.RETRY, DownloadUserActionPolicy.primary(DownloadStatus.CANCELED))
        assertNull(DownloadUserActionPolicy.primary(DownloadStatus.UNKNOWN))
        assertEquals(DownloadUserAction.PLAY_OFFLINE, DownloadUserActionPolicy.primary(DownloadStatus.COMPLETED))
    }

    @Test
    fun removeIsSeparateFromActiveTransferAction() {
        assertFalse(DownloadUserActionPolicy.canRemove(DownloadStatus.QUEUED))
        assertFalse(DownloadUserActionPolicy.canRemove(DownloadStatus.DOWNLOADING))
        assertTrue(DownloadUserActionPolicy.canRemove(DownloadStatus.PAUSED))
        assertTrue(DownloadUserActionPolicy.canRemove(DownloadStatus.COMPLETED))
    }
}
