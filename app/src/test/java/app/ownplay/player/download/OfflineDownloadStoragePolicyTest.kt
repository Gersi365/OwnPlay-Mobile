package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadStoragePolicyTest {
    @Test
    fun acceptsLargeDownloadWhenReserveStillRemains() {
        val gib = 1024L * 1024L * 1024L
        assertTrue(
            hasEnoughOfflineDownloadSpace(
                usableSpaceBytes = 16L * gib,
                requiredBytes = 12L * gib,
            ),
        )
    }

    @Test
    fun rejectsLargeDownloadWhenItWouldConsumeReserve() {
        val gib = 1024L * 1024L * 1024L
        assertFalse(
            hasEnoughOfflineDownloadSpace(
                usableSpaceBytes = 12L * gib + 128L * 1024L * 1024L,
                requiredBytes = 12L * gib,
            ),
        )
    }

    @Test
    fun rejectsWhenFreeSpaceIsAlreadyBelowReserve() {
        assertFalse(
            hasEnoughOfflineDownloadSpace(
                usableSpaceBytes = OFFLINE_DOWNLOAD_FREE_SPACE_RESERVE_BYTES - 1L,
                requiredBytes = 0L,
            ),
        )
    }

    @Test
    fun convertsFileSystemBlocksToUsableBytes() {
        assertEquals(
            16_384L,
            measuredUsableSpaceBytes(
                availableBlocks = 4L,
                fragmentSizeBytes = 4_096L,
            ),
        )
    }

    @Test
    fun saturatesFileSystemSpaceMeasurementOnOverflow() {
        assertEquals(
            Long.MAX_VALUE,
            measuredUsableSpaceBytes(
                availableBlocks = Long.MAX_VALUE,
                fragmentSizeBytes = 4_096L,
            ),
        )
    }

    @Test
    fun invalidFileSystemMeasurementIsUnknown() {
        assertNull(
            measuredUsableSpaceBytes(
                availableBlocks = -1L,
                fragmentSizeBytes = 4_096L,
            ),
        )
        assertNull(
            measuredUsableSpaceBytes(
                availableBlocks = 1L,
                fragmentSizeBytes = 0L,
            ),
        )
    }

    @Test
    fun unknownSpaceMeasurementDoesNotProduceFalsePreflightFailure() {
        assertFalse(
            shouldFailOfflineDownloadPreflight(
                usableSpaceBytes = null,
                requiredBytes = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun knownInsufficientSpaceStillFailsPreflight() {
        assertTrue(
            shouldFailOfflineDownloadPreflight(
                usableSpaceBytes = OFFLINE_DOWNLOAD_FREE_SPACE_RESERVE_BYTES,
                requiredBytes = 1L,
            ),
        )
    }
}
