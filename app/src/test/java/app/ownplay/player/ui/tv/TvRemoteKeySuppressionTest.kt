package app.ownplay.player.ui.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteKeySuppressionTest {
    @Test
    fun interleavedKeysKeepIndependentSuppressionUntilTheirOwnRelease() {
        val suppression = TvRemoteKeySuppression()

        suppression.suppress(23)
        suppression.allow(4)

        assertTrue(suppression.consumeRelease(23))
        assertFalse(suppression.consumeRelease(4))
    }

    @Test
    fun allowingOneKeyDoesNotClearAnotherSuppressedKey() {
        val suppression = TvRemoteKeySuppression()

        suppression.suppress(23)
        suppression.suppress(66)
        suppression.allow(66)

        assertTrue(suppression.consumeRelease(23))
        assertFalse(suppression.consumeRelease(66))
    }

    @Test
    fun clearDropsAllPendingSuppressedReleases() {
        val suppression = TvRemoteKeySuppression()

        suppression.suppress(23)
        suppression.suppress(4)
        suppression.clear()

        assertFalse(suppression.consumeRelease(23))
        assertFalse(suppression.consumeRelease(4))
    }
}
