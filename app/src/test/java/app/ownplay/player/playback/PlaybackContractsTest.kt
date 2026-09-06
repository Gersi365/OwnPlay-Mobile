package app.ownplay.player.playback

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackContractsTest {
    @Test
    fun requestCarriesOpaqueIdentityAndRedactsItFromRendering() {
        val request = PlaybackRequest(
            sourceId = "https://source.example.test?token=source-secret",
            channelId = "https://stream.example.test/live?token=stream-secret",
            navigationContext = PlaybackNavigationContext(
                previousChannelId = "previous-secret",
                nextChannelId = "next-secret",
            ),
        )

        val rendered = request.toString()
        assertFalse(rendered.contains("source-secret"))
        assertFalse(rendered.contains("stream-secret"))
        assertFalse(rendered.contains("previous-secret"))
        assertFalse(rendered.contains("next-secret"))
        assertTrue(rendered.contains("<opaque>"))
    }

    @Test
    fun navigationContextReturnsOnlyExplicitNeighbors() {
        val request = PlaybackRequest(
            sourceId = "source",
            channelId = "current",
            navigationContext = PlaybackNavigationContext(
                previousChannelId = "previous",
                nextChannelId = "next",
            ),
        )

        assertEquals("previous", request.navigationTarget(PlaybackNavigationDirection.PREVIOUS))
        assertEquals("next", request.navigationTarget(PlaybackNavigationDirection.NEXT))
        assertNull(PlaybackRequest("source", "channel").navigationTarget(PlaybackNavigationDirection.NEXT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsBlankSourceIdentity() {
        PlaybackRequest(sourceId = " ", channelId = "channel")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestRejectsCurrentChannelAsNextNeighbor() {
        PlaybackRequest(
            sourceId = "source",
            channelId = "channel",
            navigationContext = PlaybackNavigationContext(
                previousChannelId = null,
                nextChannelId = "channel",
            ),
        )
    }

    @Test
    fun containerExtensionValidationIsLocaleStable() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val request = PlaybackRequest(
                sourceId = "source",
                channelId = "episode",
                mediaKind = PlaybackMediaKind.SERIES_EPISODE,
                providerStreamId = 42,
                containerExtension = "AVI",
            )

            assertEquals("AVI", request.containerExtension)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun failureRetryabilityIsConservativeAndExplicit() {
        assertTrue(PlaybackFailure(PlaybackFailureCategory.NETWORK_UNAVAILABLE).retryable)
        assertTrue(PlaybackFailure(PlaybackFailureCategory.TIMEOUT).retryable)
        assertTrue(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE).retryable)
        assertFalse(PlaybackFailure(PlaybackFailureCategory.AUTHENTICATION_FAILURE).retryable)
        assertFalse(PlaybackFailure(PlaybackFailureCategory.UNSUPPORTED_MEDIA).retryable)
        assertFalse(PlaybackFailure(PlaybackFailureCategory.UNKNOWN).retryable)
    }

    @Test
    fun reducerFollowsDeterministicStartPlayPauseRetryStopLifecycle() {
        val request = PlaybackRequest(sourceId = "source", channelId = "channel")

        val loading = PlaybackReducer.reduce(PlaybackState.Idle, PlaybackEvent.Start(request))
        assertEquals(PlaybackState.Loading(request), loading)

        val playing = PlaybackReducer.reduce(loading, PlaybackEvent.Prepared)
        assertEquals(PlaybackState.Playing(request), playing)

        val buffering = PlaybackReducer.reduce(playing, PlaybackEvent.Buffer)
        assertEquals(PlaybackState.Playing(request, buffering = true), buffering)

        val recovered = PlaybackReducer.reduce(buffering, PlaybackEvent.Prepared)
        assertEquals(PlaybackState.Playing(request), recovered)

        val paused = PlaybackReducer.reduce(recovered, PlaybackEvent.Pause)
        assertEquals(PlaybackState.Paused(request), paused)

        val resumed = PlaybackReducer.reduce(paused, PlaybackEvent.Play)
        assertEquals(PlaybackState.Playing(request), resumed)

        val failure = PlaybackFailure(PlaybackFailureCategory.TIMEOUT)
        val failed = PlaybackReducer.reduce(resumed, PlaybackEvent.Fail(failure))
        assertEquals(PlaybackState.Failed(request, failure), failed)

        val retrying = PlaybackReducer.reduce(failed, PlaybackEvent.Retry)
        assertEquals(PlaybackState.Loading(request), retrying)

        assertEquals(PlaybackState.Idle, PlaybackReducer.reduce(retrying, PlaybackEvent.Stop))
    }

    @Test
    fun reducerAllowsPauseWhileBuffering() {
        val request = PlaybackRequest(sourceId = "source", channelId = "channel")
        val buffering = PlaybackState.Playing(request, buffering = true)

        assertEquals(
            PlaybackState.Paused(request),
            PlaybackReducer.reduce(buffering, PlaybackEvent.Pause),
        )
    }

    @Test
    fun reducerDoesNotRetryNonRetryableFailure() {
        val request = PlaybackRequest(sourceId = "source", channelId = "channel")
        val failed = PlaybackState.Failed(
            request = request,
            failure = PlaybackFailure(PlaybackFailureCategory.UNSUPPORTED_MEDIA),
        )

        assertEquals(failed, PlaybackReducer.reduce(failed, PlaybackEvent.Retry))
    }

    @Test
    fun reducerIgnoresFailureWithoutActiveRequest() {
        val failure = PlaybackFailure(PlaybackFailureCategory.UNKNOWN)
        assertEquals(PlaybackState.Idle, PlaybackReducer.reduce(PlaybackState.Idle, PlaybackEvent.Fail(failure)))
    }
}
