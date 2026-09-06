package app.ownplay.player.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgCacheFreshnessTest {
    @Test
    fun entryIsFreshInsideTtlWindow() {
        assertTrue(
            EpgCacheFreshness.isFresh(
                loadedAtEpochSeconds = 1_000L,
                nowEpochSeconds = 1_299L,
                ttlSeconds = 300L,
            ),
        )
    }

    @Test
    fun entryExpiresAtTtlBoundary() {
        assertFalse(
            EpgCacheFreshness.isFresh(
                loadedAtEpochSeconds = 1_000L,
                nowEpochSeconds = 1_300L,
                ttlSeconds = 300L,
            ),
        )
    }

    @Test
    fun backwardClockJumpExpiresEntryInsteadOfExtendingIt() {
        assertFalse(
            EpgCacheFreshness.isFresh(
                loadedAtEpochSeconds = 1_000L,
                nowEpochSeconds = 900L,
                ttlSeconds = 300L,
            ),
        )
    }
}
