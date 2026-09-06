package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDurationTest {
    @Test
    fun convertsPositiveSecondsToMilliseconds() {
        assertEquals(3_600_000L, MediaDuration.secondsToMillis(3_600L))
    }

    @Test
    fun rejectsNonPositiveSeconds() {
        assertNull(MediaDuration.secondsToMillis(null))
        assertNull(MediaDuration.secondsToMillis(0L))
        assertNull(MediaDuration.secondsToMillis(-1L))
    }

    @Test
    fun rejectsValuesThatWouldOverflowMilliseconds() {
        val largestSafeSeconds = Long.MAX_VALUE / 1_000L

        assertEquals(
            largestSafeSeconds * 1_000L,
            MediaDuration.secondsToMillis(largestSafeSeconds),
        )
        assertNull(MediaDuration.secondsToMillis(largestSafeSeconds + 1L))
    }
}
