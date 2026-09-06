package app.ownplay.player.source.xtream

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.XtreamCredentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class XtreamCredentialPreflightTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun vodCatalogRejectsBlankCredentialsBeforeNetwork() = runBlocking {
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodStreams(
            serverUrl = server.url("/").toString(),
            credentials = XtreamCredentials(" ", ""),
        )

        assertEquals(SourceResult.Failure(SourceError.InvalidCredentials), result)
        assertEquals(0, server.requestCount)
    }
}
