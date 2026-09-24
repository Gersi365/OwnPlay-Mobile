package app.ownplay.mobile.downloads.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionPoliciesTest {
    @Test
    fun finiteExtensionRejectsPlaylistFormatsAndTraversal() {
        assertEquals("mp4", DownloadFilePolicy.normalizeFiniteExtension(".MP4"))
        assertNull(DownloadFilePolicy.normalizeFiniteExtension("m3u8"))
        assertNull(DownloadFilePolicy.normalizeFiniteExtension("../mp4"))
    }

    @Test
    fun providerNamesAreSanitizedBeforeStoragePathUse() {
        assertEquals("Series_Name", DownloadFilePolicy.safeSegment("Series/Name", "Series"))
        assertEquals("Series", DownloadFilePolicy.safeSegment("...", "Series"))
        assertFalse(DownloadFilePolicy.fileName("Movie/Title", "mp4", "download:abcdef123456").contains('/'))
    }

    @Test
    fun episodeFileNameIsDeterministicAndIncludesStableSuffix() {
        assertEquals(
            "S02E03 - Pilot - abcdef123456.mkv",
            DownloadFilePolicy.episodeFileName(
                title = "Pilot",
                seasonNumber = 2,
                episodeNumber = 3,
                extension = "mkv",
                identitySuffix = "download:abcdef123456",
            ),
        )
        assertEquals("Season 02", DownloadFilePolicy.seasonDirectory(2))
    }

    @Test
    fun pendingStorageNameIsDeterministicAndDoesNotExposeProviderTitle() {
        val downloadId = DownloadId("download:abcdef1234567890")
        val name = DownloadPendingNamePolicy.stagingDisplayName(downloadId)

        assertEquals("ownplay-abcdef1234567890.part", name)
        assertFalse(name.contains("Movie"))
        assertFalse(name.contains('/'))
    }

    @Test
    fun integrityRequiresNonZeroExpectedAndStoredAgreement() {
        assertTrue(DownloadTransferIntegrityPolicy.isValid(100L, 100L, 100L))
        assertTrue(DownloadTransferIntegrityPolicy.isValid(100L, null, 100L))
        assertFalse(DownloadTransferIntegrityPolicy.isValid(0L, null, 0L))
        assertFalse(DownloadTransferIntegrityPolicy.isValid(100L, 101L, 100L))
        assertFalse(DownloadTransferIntegrityPolicy.isValid(100L, 100L, null))
    }

    @Test
    fun progressUpdatesAreThrottledByBytesOrTimeButFinalAlwaysPublishes() {
        val policy = DownloadProgressThrottlePolicy(minByteDelta = 100L, minTimeDeltaMs = 1_000L)
        assertFalse(policy.shouldPublish(0L, 0L, 50L, 500L))
        assertTrue(policy.shouldPublish(0L, 0L, 100L, 500L))
        assertTrue(policy.shouldPublish(0L, 0L, 50L, 1_000L))
        assertTrue(policy.shouldPublish(50L, 500L, 51L, 501L, final = true))
    }
}
