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

class XtreamShortEpgValidationTest {
    private lateinit var server: MockWebServer
    private val credentials = XtreamCredentials("fixture-user", "fixture-password")

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
    fun invalidStreamIdsFailBeforeNetwork() = runBlocking {
        val client = XtreamClient(allowCleartext = true)

        listOf("0", "-1", "not-a-number", " ").forEach { streamId ->
            assertEquals(
                SourceResult.Failure(SourceError.MalformedResponse),
                client.getShortEpg(
                    serverUrl = server.url("/").toString(),
                    credentials = credentials,
                    streamId = streamId,
                ),
            )
        }

        assertEquals(0, server.requestCount)
    }
}
