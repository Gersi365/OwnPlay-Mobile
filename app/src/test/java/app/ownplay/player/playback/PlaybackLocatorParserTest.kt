package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLocatorParserTest {
    @Test
    fun directDescriptorPreservesPayloadButRedactsRendering() {
        val secretLocator = "https://example.test/live.m3u8?token=secret|part"
        val result = PlaybackLocatorParser.parse("ownplay-locator-v1|direct|$secretLocator")

        val success = result as PlaybackLocatorParseResult.Success
        assertEquals(ParsedPlaybackLocator.Direct(secretLocator), success.locator)
        assertFalse(success.toString().contains("secret"))
        assertFalse(success.toString().contains(secretLocator))
        assertTrue(success.toString().contains("<redacted>"))
    }

    @Test
    fun xtreamLiveDescriptorParsesCanonicalPositiveStreamId() {
        val result = PlaybackLocatorParser.parse("ownplay-locator-v1|xtream-live|42")
        assertEquals(
            PlaybackLocatorParseResult.Success(ParsedPlaybackLocator.XtreamLive(42)),
            result,
        )
    }

    @Test
    fun malformedDescriptorFailsWithoutEchoingInput() {
        val secret = "token=super-secret"
        val result = PlaybackLocatorParser.parse("ownplay-locator-v1|$secret")

        assertEquals(
            PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.MALFORMED),
            result,
        )
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun unsupportedVersionAndKindAreExplicit() {
        assertEquals(
            PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.UNSUPPORTED_VERSION),
            PlaybackLocatorParser.parse("ownplay-locator-v2|direct|https://example.test/live"),
        )
        assertEquals(
            PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.UNSUPPORTED_KIND),
            PlaybackLocatorParser.parse("ownplay-locator-v1|future-kind|sensitive-payload"),
        )
    }

    @Test
    fun invalidPayloadsAreRejectedConservatively() {
        val invalid = listOf(
            "ownplay-locator-v1|direct|",
            "ownplay-locator-v1|direct|   ",
            "ownplay-locator-v1|xtream-live|",
            "ownplay-locator-v1|xtream-live|0",
            "ownplay-locator-v1|xtream-live|-1",
            "ownplay-locator-v1|xtream-live|42x",
            "ownplay-locator-v1|xtream-live|999999999999999999999999",
        )

        invalid.forEach { descriptor ->
            assertEquals(
                PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.INVALID_PAYLOAD),
                PlaybackLocatorParser.parse(descriptor),
            )
        }
    }
}
