package app.ownplay.player.source.m3u

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uXmlTvClientTest {
    @Test
    fun load_emptyEpgUrlListReturnsEmptySnapshotWithoutNetwork() = runBlocking {
        val client = M3uXmlTvClient()

        val result = client.load(
            epgUrls = emptyList(),
            channelIds = setOf("channel-1"),
            allowCleartext = false,
        )

        assertEquals(
            SourceResult.Success(M3uXmlTvSnapshot(emptyMap())),
            result,
        )
    }

    @Test
    fun load_emptyChannelSetReturnsEmptySnapshotWithoutNetwork() = runBlocking {
        val client = M3uXmlTvClient()

        val result = client.load(
            epgUrls = listOf("https://epg.example/guide.xml"),
            channelIds = emptySet(),
            allowCleartext = false,
        )

        assertEquals(
            SourceResult.Success(M3uXmlTvSnapshot(emptyMap())),
            result,
        )
    }

    @Test
    fun load_rejectsHttpEpgWithoutExplicitOptInBeforeNetwork() = runBlocking {
        val client = M3uXmlTvClient()

        val result = client.load(
            epgUrls = listOf("http://epg.example/guide.xml"),
            channelIds = setOf("channel-1"),
            allowCleartext = false,
        )

        assertEquals(
            SourceResult.Failure(SourceError.CleartextTransportRequiresOptIn),
            result,
        )
    }
}
