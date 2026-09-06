package app.ownplay.player.source.xtream

import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.XtreamCredentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XtreamNumericMetadataSanitizationTest {
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
    fun vodCatalogAndDetailsIgnoreInvalidNumericMetadata() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"stream_id":42,"name":"Movie","rating":"NaN","added":"-1"},
                      {"stream_id":" 43 ","name":"Rated Movie","rating":" 8.1 ","added":" 0 "}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "info": {
                        "name": "Movie",
                        "duration_secs": " -1 ",
                        "rating": "Infinity"
                      },
                      "movie_data": {"stream_id":42}
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val catalogResult = client.getVodStreams(server.url("/").toString(), credentials)
        assertTrue(catalogResult is SourceResult.Success)
        val movies = (catalogResult as SourceResult.Success).value
        val invalidMovie = movies.first { it.streamId == 42 }
        val validMovie = movies.first { it.streamId == 43 }
        assertNull(invalidMovie.rating)
        assertNull(invalidMovie.addedAtEpochSeconds)
        assertEquals(8.1, validMovie.rating!!, 0.001)
        assertEquals(0L, validMovie.addedAtEpochSeconds)

        val detailsResult = client.getVodInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            vodId = 42,
        )
        assertTrue(detailsResult is SourceResult.Success)
        val details = (detailsResult as SourceResult.Success).value
        assertNull(details.rating)
        assertNull(details.durationSeconds)
    }

    @Test
    fun seriesCatalogAndEpisodeDetailsIgnoreInvalidNumericMetadata() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"series_id":501,"name":"Series","rating":"NaN","last_modified":"-1"},
                      {"series_id":" 502 ","name":"Rated Series","rating":" 8.4 ","last_modified":" 0 "}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "info": {"name":"Series","rating":"Infinity"},
                      "episodes": {
                        "1": [
                          {
                            "id":" 1001 ",
                            "episode_num":" 1 ",
                            "season":" 1 ",
                            "title":"Episode",
                            "added":" -1 ",
                            "info":{"duration_secs":" 0 ","rating":"NaN"}
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamSeriesClient(allowCleartext = true)

        val catalogResult = client.getSeries(server.url("/").toString(), credentials)
        assertTrue(catalogResult is SourceResult.Success)
        val series = (catalogResult as SourceResult.Success).value
        val invalidSeries = series.first { it.seriesId == 501 }
        val validSeries = series.first { it.seriesId == 502 }
        assertNull(invalidSeries.rating)
        assertNull(invalidSeries.lastModifiedEpochSeconds)
        assertEquals(8.4, validSeries.rating!!, 0.001)
        assertEquals(0L, validSeries.lastModifiedEpochSeconds)

        val detailsResult = client.getSeriesInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            seriesId = 501,
        )
        assertTrue(detailsResult is SourceResult.Success)
        val details = (detailsResult as SourceResult.Success).value
        assertNull(details.rating)
        assertNull(details.episodes.single().rating)
        assertNull(details.episodes.single().durationSeconds)
        assertNull(details.episodes.single().addedAtEpochSeconds)
    }
}
