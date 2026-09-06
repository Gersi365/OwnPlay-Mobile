package app.ownplay.player.playback

sealed interface ParsedPlaybackLocator {
    data class Direct(
        val locator: String,
    ) : ParsedPlaybackLocator {
        override fun toString(): String = "ParsedPlaybackLocator.Direct(locator=<redacted>)"
    }

    data class XtreamLive(
        val streamId: Int,
    ) : ParsedPlaybackLocator
}

enum class PlaybackLocatorParseFailureReason {
    MALFORMED,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_KIND,
    INVALID_PAYLOAD,
}

sealed interface PlaybackLocatorParseResult {
    data class Success(
        val locator: ParsedPlaybackLocator,
    ) : PlaybackLocatorParseResult

    data class Failure(
        val reason: PlaybackLocatorParseFailureReason,
    ) : PlaybackLocatorParseResult
}

object PlaybackLocatorParser {
    private const val SUPPORTED_PREFIX = "ownplay-locator-v1"
    private const val DIRECT_KIND = "direct"
    private const val XTREAM_LIVE_KIND = "xtream-live"

    fun parse(descriptor: String): PlaybackLocatorParseResult {
        val parts = descriptor.split('|', limit = 3)
        if (parts.size != 3) {
            return PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.MALFORMED)
        }

        val prefix = parts[0]
        val kind = parts[1]
        val payload = parts[2]

        if (prefix != SUPPORTED_PREFIX) {
            return PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.UNSUPPORTED_VERSION)
        }

        return when (kind) {
            DIRECT_KIND -> parseDirect(payload)
            XTREAM_LIVE_KIND -> parseXtreamLive(payload)
            else -> PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.UNSUPPORTED_KIND)
        }
    }

    private fun parseDirect(payload: String): PlaybackLocatorParseResult =
        if (payload.isBlank()) {
            PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.INVALID_PAYLOAD)
        } else {
            PlaybackLocatorParseResult.Success(ParsedPlaybackLocator.Direct(payload))
        }

    private fun parseXtreamLive(payload: String): PlaybackLocatorParseResult {
        if (payload.isEmpty() || payload.any { character -> !character.isDigit() }) {
            return PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.INVALID_PAYLOAD)
        }

        val streamId = payload.toIntOrNull()?.takeIf { it > 0 }
            ?: return PlaybackLocatorParseResult.Failure(PlaybackLocatorParseFailureReason.INVALID_PAYLOAD)

        return PlaybackLocatorParseResult.Success(ParsedPlaybackLocator.XtreamLive(streamId))
    }
}
