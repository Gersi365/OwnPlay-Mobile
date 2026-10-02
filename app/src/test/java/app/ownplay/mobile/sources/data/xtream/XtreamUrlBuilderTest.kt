package app.ownplay.mobile.sources.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamUrlBuilderTest {
    @Test
    fun `player api credentials and action are encoded deterministically`() {
        val url = XtreamUrlBuilder.playerApi(
            baseUrl = "https://example.com/",
            username = "user name",
            password = "p@ss&word",
            action = "get_live_streams",
            extraParameters = mapOf("category_id" to "10"),
        )

        assertEquals(
            "https://example.com/player_api.php?username=user%20name&password=p%40ss%26word&action=get_live_streams&category_id=10",
            url,
        )
    }

    @Test
    fun `live stream path encodes credentials`() {
        val url = XtreamUrlBuilder.liveStream(
            baseUrl = "https://example.com",
            username = "user/name",
            password = "pass word",
            streamId = "42",
            extension = ".m3u8",
        )

        assertEquals(
            "https://example.com/live/user%2Fname/pass%20word/42.m3u8",
            url,
        )
    }

    @Test
    fun `catch up path uses provider timezone and rounded duration`() {
        val url = XtreamUrlBuilder.catchUpStream(
            baseUrl = "https://example.com",
            username = "user name",
            password = "p@ss",
            streamId = "42",
            extension = "ts",
            startEpochSeconds = 1_600_000_000L,
            durationSeconds = 3_601L,
            providerTimezone = "UTC",
        )

        assertEquals(
            "https://example.com/timeshift/user%20name/p%40ss/61/2020-09-13%3A12-26/42.ts",
            url,
        )
    }

    @Test
    fun `builder rejects fragment from base url`() {
        val failed = runCatching {
            XtreamUrlBuilder.playerApi(
                baseUrl = "https://example.com/#secret",
                username = "u",
                password = "p",
            )
        }.isFailure

        assertTrue(failed)
    }
}
