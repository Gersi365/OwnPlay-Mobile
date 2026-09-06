package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRetryDefaultsTest {
    @Test
    fun defaultRetryPolicyAddsOneBoundedRecoveryAttempt() {
        val policy = PlaybackRetryPolicy()

        assertEquals(3, policy.maxAutomaticAttempts)
        assertEquals(750L, policy.delayBeforeAttempt(1))
        assertEquals(1_500L, policy.delayBeforeAttempt(2))
        assertEquals(3_000L, policy.delayBeforeAttempt(3))
    }
}
