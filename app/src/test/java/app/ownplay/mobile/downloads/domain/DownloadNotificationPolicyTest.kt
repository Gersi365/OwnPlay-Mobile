package app.ownplay.mobile.downloads.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationPolicyTest {
    @Test
    fun notificationIdsAreStablePositiveAndSeparateByPurpose() {
        val id = DownloadId("download:abc")
        val foreground = DownloadNotificationPolicy.foregroundNotificationId(id)
        val result = DownloadNotificationPolicy.resultNotificationId(id)

        assertTrue(foreground > 0)
        assertTrue(result > 0)
        assertNotEquals(foreground, result)
        assertEquals(foreground, DownloadNotificationPolicy.foregroundNotificationId(id))
    }

    @Test
    fun progressPercentIsBoundedAndUnknownWithoutReliableTotal() {
        assertNull(DownloadNotificationPolicy.progressPercent(50L, null))
        assertNull(DownloadNotificationPolicy.progressPercent(-1L, 100L))
        assertEquals(0, DownloadNotificationPolicy.progressPercent(0L, 100L))
        assertEquals(50, DownloadNotificationPolicy.progressPercent(50L, 100L))
        assertEquals(100, DownloadNotificationPolicy.progressPercent(150L, 100L))
    }

    @Test
    fun notificationActionsTargetOnlyCompatiblePersistedStates() {
        assertTrue(DownloadNotificationPolicy.canPause(DownloadStatus.WAITING_FOR_WIFI))
        assertTrue(DownloadNotificationPolicy.canPause(DownloadStatus.QUEUED))
        assertTrue(DownloadNotificationPolicy.canPause(DownloadStatus.DOWNLOADING))
        assertFalse(DownloadNotificationPolicy.canPause(DownloadStatus.PAUSED))
        assertFalse(DownloadNotificationPolicy.canPause(DownloadStatus.COMPLETED))

        assertTrue(DownloadNotificationPolicy.canResume(DownloadStatus.PAUSED))
        assertFalse(DownloadNotificationPolicy.canResume(DownloadStatus.QUEUED))
        assertFalse(DownloadNotificationPolicy.canResume(DownloadStatus.DOWNLOADING))
        assertFalse(DownloadNotificationPolicy.canResume(DownloadStatus.COMPLETED))

        assertTrue(DownloadNotificationPolicy.canCancel(DownloadStatus.WAITING_FOR_WIFI))
        assertTrue(DownloadNotificationPolicy.canCancel(DownloadStatus.QUEUED))
        assertTrue(DownloadNotificationPolicy.canCancel(DownloadStatus.DOWNLOADING))
        assertTrue(DownloadNotificationPolicy.canCancel(DownloadStatus.PAUSED))
        assertFalse(DownloadNotificationPolicy.canCancel(DownloadStatus.COMPLETED))
        assertFalse(DownloadNotificationPolicy.canCancel(DownloadStatus.FAILED))
        assertFalse(DownloadNotificationPolicy.canCancel(DownloadStatus.MISSING))
        assertFalse(DownloadNotificationPolicy.canCancel(DownloadStatus.CANCELED))
        assertFalse(DownloadNotificationPolicy.canCancel(DownloadStatus.UNKNOWN))
    }
    @Test
    fun notificationPermissionIsRequestedOnlyOnceOnAndroid13OrNewer() {
        assertTrue(DownloadNotificationPermissionPromptPolicy.shouldRequest(33, false, false))
        assertFalse(DownloadNotificationPermissionPromptPolicy.shouldRequest(33, false, true))
        assertFalse(DownloadNotificationPermissionPromptPolicy.shouldRequest(33, true, false))
        assertFalse(DownloadNotificationPermissionPromptPolicy.shouldRequest(32, false, false))
    }

}
