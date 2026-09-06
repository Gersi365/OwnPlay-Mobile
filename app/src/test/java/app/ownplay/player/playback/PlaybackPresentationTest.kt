package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPresentationTest {
    private val request = PlaybackRequest(
        sourceId = "source",
        channelId = "channel",
    )

    @Test
    fun resizeModesCycleDeterministically() {
        assertEquals(PlaybackResizeMode.FILL, PlaybackResizeMode.FIT.next())
        assertEquals(PlaybackResizeMode.ZOOM, PlaybackResizeMode.FILL.next())
        assertEquals(PlaybackResizeMode.FIT, PlaybackResizeMode.ZOOM.next())
    }

    @Test
    fun loadingAndPlayPauseActionsFollowPlaybackState() {
        val loading = PlaybackPresentationPolicy.controlsFor(PlaybackState.Loading(request))
        assertTrue(loading.showLoading)
        assertFalse(loading.canPlay)
        assertFalse(loading.canPause)

        val playing = PlaybackPresentationPolicy.controlsFor(PlaybackState.Playing(request))
        assertFalse(playing.showLoading)
        assertTrue(playing.canPause)
        assertFalse(playing.canPlay)

        val paused = PlaybackPresentationPolicy.controlsFor(PlaybackState.Paused(request))
        assertTrue(paused.canPlay)
        assertFalse(paused.canPause)
    }

    @Test
    fun bufferingInsidePlayingStateShowsLoadingWithoutLosingPauseControl() {
        val buffering = PlaybackPresentationPolicy.controlsFor(
            PlaybackState.Playing(
                request = request,
                buffering = true,
            ),
        )

        assertTrue(buffering.showLoading)
        assertTrue(buffering.canPause)
        assertFalse(buffering.canPlay)
        assertFalse(buffering.canRetry)
    }

    @Test
    fun retryIsShownOnlyForRetryableFailures() {
        val retryable = PlaybackPresentationPolicy.controlsFor(
            PlaybackState.Failed(
                request = request,
                failure = PlaybackFailure(PlaybackFailureCategory.TIMEOUT),
            ),
        )
        assertTrue(retryable.canRetry)

        val nonRetryable = PlaybackPresentationPolicy.controlsFor(
            PlaybackState.Failed(
                request = request,
                failure = PlaybackFailure(PlaybackFailureCategory.AUTHENTICATION_FAILURE),
            ),
        )
        assertFalse(nonRetryable.canRetry)
    }
}
