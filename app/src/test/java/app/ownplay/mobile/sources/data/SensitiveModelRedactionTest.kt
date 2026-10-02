package app.ownplay.mobile.sources.data

import app.ownplay.mobile.sources.data.m3u.M3uEntry
import app.ownplay.mobile.sources.domain.SourceType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveModelRedactionTest {
    @Test
    fun `m3u entry string does not expose authenticated urls`() {
        val rendered = M3uEntry(
            name = "News",
            groupTitle = "General",
            tvgId = "news-1",
            tvgName = "News",
            logoUrl = "https://images.example/logo.png?token=logo-secret",
            streamUrl = "https://stream.example/live.m3u8?token=stream-secret",
        ).toString()

        assertTrue(rendered.contains("streamUrl=<redacted>"))
        assertTrue(rendered.contains("logoUrl=<redacted>"))
        assertFalse(rendered.contains("stream-secret"))
        assertFalse(rendered.contains("logo-secret"))
    }

    @Test
    fun `provider response string does not expose raw payload`() {
        val rendered = ProviderResponse(
            statusCode = 200,
            contentType = "application/json",
            body = "{\"username\":\"alice\",\"password\":\"provider-secret\"}",
        ).toString()

        assertTrue(rendered.contains("body=<redacted>"))
        assertFalse(rendered.contains("alice"))
        assertFalse(rendered.contains("provider-secret"))
    }

    @Test
    fun `provider catalog string does not expose remote url queries`() {
        val rendered = ProviderCatalogSnapshot(
            sourceType = SourceType.M3U,
            categories = emptyList(),
            liveChannels = listOf(
                ProviderLiveChannelRecord(
                    proposedChannelId = "channel-1",
                    providerKey = "provider-secret-key",
                    providerStreamId = null,
                    categoryProviderKey = "general",
                    name = "Channel",
                    tvgId = null,
                    tvgName = null,
                    logoUrl = "https://images.example/logo.png?token=logo-secret",
                    streamLocator = "https://stream.example/live.m3u8?token=stream-secret",
                    providerOrder = 0,
                ),
            ),
            movies = listOf(
                ProviderMovieRecord(
                    proposedMovieId = "movie-1",
                    providerStreamId = "10",
                    categoryProviderKey = null,
                    name = "Movie",
                    posterUrl = "https://images.example/poster.jpg?token=poster-secret",
                    backdropUrl = "https://images.example/backdrop.jpg?token=backdrop-secret",
                    extension = "mp4",
                    rating = null,
                    providerOrder = 0,
                ),
            ),
            series = listOf(
                ProviderSeriesRecord(
                    proposedSeriesId = "series-1",
                    providerSeriesId = "20",
                    categoryProviderKey = null,
                    name = "Series",
                    posterUrl = "https://images.example/series.jpg?token=series-secret",
                    backdropUrl = null,
                    description = null,
                    rating = null,
                    providerOrder = 0,
                ),
            ),
        ).toString()

        assertFalse(rendered.contains("provider-secret-key"))
        assertFalse(rendered.contains("stream-secret"))
        assertFalse(rendered.contains("logo-secret"))
        assertFalse(rendered.contains("poster-secret"))
        assertFalse(rendered.contains("backdrop-secret"))
        assertFalse(rendered.contains("series-secret"))
    }
}
