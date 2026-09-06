package app.ownplay.player.source.xtream

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.XtreamCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamXmlTvClientTest {
    @Test
    fun load_rejectsBlankCredentialsBeforeNetwork() = runBlocking {
        val client = XtreamXmlTvClient()

        val result = client.load(
            serverUrl = "https://provider.example/",
            credentials = XtreamCredentials(username = " ", password = "secret"),
            channelIds = setOf("channel-1"),
            allowCleartext = false,
        )

        assertEquals(SourceResult.Failure(SourceError.InvalidCredentials), result)
    }

    @Test
    fun load_emptyChannelSetDoesNotRequireCredentials() = runBlocking {
        val client = XtreamXmlTvClient()

        val result = client.load(
            serverUrl = "https://provider.example/",
            credentials = XtreamCredentials(username = "", password = ""),
            channelIds = emptySet(),
            allowCleartext = false,
        )

        assertEquals(
            SourceResult.Success(XtreamXmlTvSnapshot(emptyMap())),
            result,
        )
    }
}
