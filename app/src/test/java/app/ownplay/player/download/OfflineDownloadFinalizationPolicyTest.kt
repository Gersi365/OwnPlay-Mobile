package app.ownplay.player.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDownloadFinalizationPolicyTest {
    @Test
    fun recoversFinalizedContentWhenSizeMatches() {
        assertEquals(
            1_024L,
            OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
                finalized = true,
                actualBytes = 1_024L,
                expectedTotalBytes = 1_024L,
            ),
        )
    }

    @Test
    fun recoversFinalizedContentWhenExpectedSizeWasUnknown() {
        assertEquals(
            2_048L,
            OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
                finalized = true,
                actualBytes = 2_048L,
                expectedTotalBytes = null,
            ),
        )
    }

    @Test
    fun rejectsPendingEmptyOrSizeMismatchedContent() {
        assertNull(
            OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
                finalized = false,
                actualBytes = 1_024L,
                expectedTotalBytes = 1_024L,
            ),
        )
        assertNull(
            OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
                finalized = true,
                actualBytes = 0L,
                expectedTotalBytes = null,
            ),
        )
        assertNull(
            OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
                finalized = true,
                actualBytes = 1_023L,
                expectedTotalBytes = 1_024L,
            ),
        )
    }
}
