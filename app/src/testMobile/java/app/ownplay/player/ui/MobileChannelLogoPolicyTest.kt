package app.ownplay.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileChannelLogoPolicyTest {
    @Test
    fun fallbackUsesTrimmedInitialOrBullet() {
        assertEquals("C", mobileChannelLogoFallbackLabel("  cnn "))
        assertEquals("•", mobileChannelLogoFallbackLabel("   "))
    }

    @Test
    fun sampleSizeKeepsLongEdgeWithinBound() {
        assertEquals(1, calculateMobileLogoInSampleSize(width = 200, height = 100))
        assertEquals(2, calculateMobileLogoInSampleSize(width = 512, height = 256))
        assertEquals(8, calculateMobileLogoInSampleSize(width = 2048, height = 1024))
    }

    @Test
    fun sampleSizeFallsBackSafelyForInvalidDimensions() {
        assertEquals(1, calculateMobileLogoInSampleSize(width = 0, height = 400))
        assertEquals(1, calculateMobileLogoInSampleSize(width = 400, height = -1))
    }
}
