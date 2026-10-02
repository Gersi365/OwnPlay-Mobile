package app.ownplay.mobile.downloads.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPoliciesTest {
    @Test
    fun validWorkerLifecycleTransitionsAreAccepted() {
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.QUEUED, DownloadStatus.WAITING_FOR_WIFI))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.WAITING_FOR_WIFI, DownloadStatus.QUEUED))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.QUEUED, DownloadStatus.PAUSED))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.PAUSED, DownloadStatus.QUEUED))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED))
        assertTrue(DownloadStateTransitionPolicy.canTransition(DownloadStatus.FAILED, DownloadStatus.QUEUED))
    }

    @Test
    fun completedAndUnknownRowsDoNotRestartImplicitly() {
        assertFalse(DownloadStateTransitionPolicy.canTransition(DownloadStatus.COMPLETED, DownloadStatus.QUEUED))
        assertFalse(DownloadStateTransitionPolicy.canTransition(DownloadStatus.UNKNOWN, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun progressRejectsNegativeAndOverrunValues() {
        assertTrue(DownloadProgressPolicy.isValid(bytesDownloaded = 50L, totalBytes = 100L))
        assertTrue(DownloadProgressPolicy.isValid(bytesDownloaded = 50L, totalBytes = null))
        assertFalse(DownloadProgressPolicy.isValid(bytesDownloaded = -1L, totalBytes = 100L))
        assertFalse(DownloadProgressPolicy.isValid(bytesDownloaded = 101L, totalBytes = 100L))
        assertFalse(DownloadProgressPolicy.isValid(bytesDownloaded = 0L, totalBytes = 0L))
    }

    @Test
    fun sha256NormalizationIsStrictAndDeterministic() {
        val uppercase = "A".repeat(64)
        assertEquals("a".repeat(64), DownloadIntegrityPolicy.normalizeSha256(uppercase))
        assertNull(DownloadIntegrityPolicy.normalizeSha256("not-a-digest"))
        assertNull(DownloadIntegrityPolicy.normalizeSha256(""))
    }
}
