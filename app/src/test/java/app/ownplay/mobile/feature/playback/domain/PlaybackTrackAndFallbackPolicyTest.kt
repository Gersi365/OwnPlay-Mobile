package app.ownplay.mobile.feature.playback.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackAndFallbackPolicyTest {
    @Test
    fun vodSpeedPolicyAllowsOnlyTheProductDefinedSessionSpeeds() {
        assertEquals(
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
            PlaybackSpeedPolicy.supportedSpeeds,
        )
        assertEquals(1f, PlaybackSpeedPolicy.DEFAULT_SPEED)
        assertTrue(PlaybackSpeedPolicy.isSupported(1.25f))
        assertFalse(PlaybackSpeedPolicy.isSupported(1.1f))
    }

    @Test
    fun trackLabelUsesDeterministicHumanReadableFields() {
        assertEquals(
            "En · Commentary · Stereo",
            PlaybackTrackLabelPolicy.label(
                kind = PlaybackTrackKind.AUDIO,
                ordinal = 1,
                labelHint = "English",
                language = "en",
                codec = "aac",
                channelCount = 2,
                role = "Commentary",
            ),
        )
        assertEquals(
            "Subtitle 2",
            PlaybackTrackLabelPolicy.label(
                kind = PlaybackTrackKind.SUBTITLE,
                ordinal = 2,
                labelHint = null,
                language = null,
                codec = null,
                channelCount = null,
                role = null,
            ),
        )
    }

    @Test
    fun fallbackAllowsBoundedExplicitFailureClassesIncludingTransport() {
        assertTrue(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = true,
                alreadyAttempted = false,
                reason = PlaybackFallbackReason.DECODER_OR_FORMAT,
            ),
        )
        assertTrue(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = true,
                alreadyAttempted = false,
                reason = PlaybackFallbackReason.AUDIO_SELECTION,
            ),
        )
        assertTrue(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = true,
                alreadyAttempted = false,
                reason = PlaybackFallbackReason.PROLONGED_BUFFERING,
            ),
        )
        assertTrue(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = true,
                alreadyAttempted = false,
                reason = PlaybackFallbackReason.OTHER,
            ),
        )
        assertFalse(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = false,
                alreadyAttempted = false,
                reason = PlaybackFallbackReason.DECODER_OR_FORMAT,
            ),
        )
        assertFalse(
            PlaybackFallbackPolicy.canAttempt(
                hasFallback = true,
                alreadyAttempted = true,
                reason = PlaybackFallbackReason.DECODER_OR_FORMAT,
            ),
        )
    }
}
