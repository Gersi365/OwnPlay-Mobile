package app.ownplay.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class Media3PlaybackFailureMapperTest {
    @Test
    fun networkAndTimeoutCodesMapDeterministically() {
        assertEquals(
            PlaybackFailureCategory.NETWORK_UNAVAILABLE,
            Media3PlaybackFailureMapper.map(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            ).category,
        )
        assertEquals(
            PlaybackFailureCategory.TIMEOUT,
            Media3PlaybackFailureMapper.map(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            ).category,
        )
    }

    @Test
    fun authenticationRequiresExplicitHttpStatusEvidence() {
        assertEquals(
            PlaybackFailureCategory.AUTHENTICATION_FAILURE,
            Media3PlaybackFailureMapper.map(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatusCode = 401,
            ).category,
        )
        assertEquals(
            PlaybackFailureCategory.AUTHENTICATION_FAILURE,
            Media3PlaybackFailureMapper.map(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatusCode = 403,
            ).category,
        )
        assertEquals(
            PlaybackFailureCategory.STREAM_UNAVAILABLE,
            Media3PlaybackFailureMapper.map(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatusCode = 500,
            ).category,
        )
        assertEquals(
            PlaybackFailureCategory.STREAM_UNAVAILABLE,
            Media3PlaybackFailureMapper.map(
                errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                httpStatusCode = null,
            ).category,
        )
    }

    @Test
    fun unsupportedMediaCodesMapWithoutFreeFormErrorText() {
        assertEquals(
            PlaybackFailureCategory.UNSUPPORTED_MEDIA,
            Media3PlaybackFailureMapper.map(
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            ).category,
        )
        assertEquals(
            PlaybackFailureCategory.UNSUPPORTED_MEDIA,
            Media3PlaybackFailureMapper.map(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ).category,
        )
    }

    @Test
    fun decoderFailuresAreTerminalForCurrentMedia() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        ).forEach { errorCode ->
            val failure = Media3PlaybackFailureMapper.map(errorCode)

            assertEquals(PlaybackFailureCategory.UNSUPPORTED_MEDIA, failure.category)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun malformedContainerRemainsRetryableWithoutBeingTreatedAsDecoderFailure() {
        val failure = Media3PlaybackFailureMapper.map(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        assertEquals(PlaybackFailureCategory.STREAM_UNAVAILABLE, failure.category)
        assertTrue(failure.retryable)
    }

    @Test
    fun stuckPlayingTimeoutIsTerminalEvenWhenMedia3UsesTimeoutErrorCode() {
        val failure = Media3PlaybackFailureMapper.map(
            errorCode = PlaybackException.ERROR_CODE_TIMEOUT,
            stuckType = StuckPlayerException.STUCK_PLAYING_NO_PROGRESS,
        )

        assertEquals(PlaybackFailureCategory.UNKNOWN, failure.category)
        assertFalse(failure.retryable)
    }

    @Test
    fun stuckBufferingTimeoutRemainsRetryable() {
        val failure = Media3PlaybackFailureMapper.map(
            errorCode = PlaybackException.ERROR_CODE_TIMEOUT,
            stuckType = StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
        )

        assertEquals(PlaybackFailureCategory.TIMEOUT, failure.category)
        assertTrue(failure.retryable)
    }

    @Test
    fun surfaceDetachTimeoutIsTerminalForCurrentVideo() {
        val failure = Media3PlaybackFailureMapper.map(
            errorCode = PlaybackException.ERROR_CODE_TIMEOUT,
            hasExoTimeout = true,
        )

        assertEquals(PlaybackFailureCategory.UNKNOWN, failure.category)
        assertFalse(failure.retryable)
    }
}
