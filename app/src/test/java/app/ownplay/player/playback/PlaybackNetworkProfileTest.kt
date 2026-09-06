package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNetworkProfileTest {
    @Test
    fun unavailableProfileCarriesNoStaleNetworkCapabilities() {
        val profile = PlaybackNetworkProfile.Unavailable

        assertEquals(PlaybackNetworkState.UNAVAILABLE, profile.state)
        assertFalse(profile.validated)
        assertNull(profile.metered)
        assertNull(profile.downstreamBandwidthKbps)
        assertNull(profile.upstreamBandwidthKbps)
        assertNull(profile.measuredPlaybackBandwidthBps)
        assertTrue(profile.transports.isEmpty())
    }

    @Test
    fun connectedUnvalidatedNetworkRemainsAvailableForPlaybackAttempts() {
        val profile = PlaybackNetworkProfile(
            state = PlaybackNetworkState.AVAILABLE,
            validated = false,
            metered = true,
            downstreamBandwidthKbps = 3_000,
            upstreamBandwidthKbps = 700,
            transports = setOf(PlaybackNetworkTransport.CELLULAR),
            measuredPlaybackBandwidthBps = 2_400_000L,
        )

        assertEquals(PlaybackNetworkState.AVAILABLE, profile.state)
        assertFalse(profile.validated)
        assertEquals(true, profile.metered)
        assertEquals(3_000, profile.downstreamBandwidthKbps)
        assertEquals(2_400_000L, profile.measuredPlaybackBandwidthBps)
        assertEquals(setOf(PlaybackNetworkTransport.CELLULAR), profile.transports)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableProfileRejectsStaleBandwidth() {
        PlaybackNetworkProfile(
            state = PlaybackNetworkState.UNAVAILABLE,
            downstreamBandwidthKbps = 1_000,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableProfileRejectsMeasuredPlaybackBandwidth() {
        PlaybackNetworkProfile(
            state = PlaybackNetworkState.UNAVAILABLE,
            measuredPlaybackBandwidthBps = 1_000_000L,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun profileRejectsNonPositiveBandwidth() {
        PlaybackNetworkProfile(
            state = PlaybackNetworkState.AVAILABLE,
            downstreamBandwidthKbps = 0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun profileRejectsNonPositiveMeasuredPlaybackBandwidth() {
        PlaybackNetworkProfile(
            state = PlaybackNetworkState.AVAILABLE,
            measuredPlaybackBandwidthBps = 0L,
        )
    }
}
