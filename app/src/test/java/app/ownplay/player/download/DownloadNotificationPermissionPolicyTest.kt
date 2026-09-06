package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationPermissionPolicyTest {
    @Test
    fun preAndroid13DoesNotRequireRuntimePermission() {
        val state = DownloadNotificationPermissionPolicy.resolve(
            sdkInt = 32,
            permissionGranted = false,
            requestAttempted = false,
        )
        assertEquals(DownloadNotificationPermissionState.NOT_REQUIRED, state)
        assertFalse(DownloadNotificationPermissionPolicy.shouldRequest(state))
    }

    @Test
    fun grantedPermissionWinsOverPriorRequestState() {
        val state = DownloadNotificationPermissionPolicy.resolve(
            sdkInt = 36,
            permissionGranted = true,
            requestAttempted = true,
        )
        assertEquals(DownloadNotificationPermissionState.GRANTED, state)
        assertFalse(DownloadNotificationPermissionPolicy.shouldRequest(state))
    }

    @Test
    fun firstRelevantDownloadRequestsPermissionOnce() {
        val state = DownloadNotificationPermissionPolicy.resolve(
            sdkInt = 33,
            permissionGranted = false,
            requestAttempted = false,
        )
        assertEquals(DownloadNotificationPermissionState.NEVER_REQUESTED, state)
        assertTrue(DownloadNotificationPermissionPolicy.shouldRequest(state))
    }

    @Test
    fun previouslyAttemptedAndStillNotGrantedDoesNotReprompt() {
        val state = DownloadNotificationPermissionPolicy.resolve(
            sdkInt = 36,
            permissionGranted = false,
            requestAttempted = true,
        )
        assertEquals(DownloadNotificationPermissionState.DENIED, state)
        assertFalse(DownloadNotificationPermissionPolicy.shouldRequest(state))
    }
}
