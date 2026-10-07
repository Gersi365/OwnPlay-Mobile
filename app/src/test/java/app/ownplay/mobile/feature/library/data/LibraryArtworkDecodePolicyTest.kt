package app.ownplay.mobile.feature.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryArtworkDecodePolicyTest {
    @Test
    fun sampleSizeUsesPowerOfTwoUntilLargestDecodedDimensionFitsBound() {
        assertEquals(1, LibraryArtworkDecodePolicy.sampleSize(800, 600))
        assertEquals(4, LibraryArtworkDecodePolicy.sampleSize(4_000, 3_000))
        assertEquals(8, LibraryArtworkDecodePolicy.sampleSize(5_000, 2_500))
    }

    @Test
    fun unknownBoundsFallBackToSingleSample() {
        assertEquals(1, LibraryArtworkDecodePolicy.sampleSize(0, 0))
        assertEquals(1, LibraryArtworkDecodePolicy.sampleSize(-1, 100))
    }

    @Test
    fun customDecodeBoundIsRespected() {
        assertEquals(4, LibraryArtworkDecodePolicy.sampleSize(1_600, 900, maxDimensionPx = 512))
    }

    @Test
    fun nonPositiveDecodeBoundIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LibraryArtworkDecodePolicy.sampleSize(100, 100, maxDimensionPx = 0)
        }
    }
}
