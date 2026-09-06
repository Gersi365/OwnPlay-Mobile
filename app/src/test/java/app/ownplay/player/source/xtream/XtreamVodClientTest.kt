package app.ownplay.player.source.xtream

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceResult
import app.ownplay.player.source.credential.XtreamCredentials
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XtreamVodClientTest {
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
    fun getVodCategories_usesVodActionAndParsesProviderOrderInput() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"category_id":"100","category_name":"Action","parent_id":0},
                      {"category_id":"200","category_name":"Drama","parent_id":"1"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodCategories(server.url("/").toString(), credentials)

        assertTrue(result is SourceResult.Success)
        val categories = (result as SourceResult.Success).value
        assertEquals(listOf("Action", "Drama"), categories.map { it.name })
        assertEquals("0", categories.first().parentId)
        assertEquals("get_vod_categories", server.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun getVodStreams_parsesMetadataWithoutRequiringOptionalFields() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {
                        "stream_id": 501,
                        "name": "Movie One",
                        "category_id": "100",
                        "stream_icon": "https://images.example/poster.jpg",
                        "container_extension": "mkv",
                        "rating": "7.8",
                        "added": "1700000000",
                        "direct_source": ""
                      },
                      {
                        "stream_id": "502",
                        "name": "Movie Two"
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodStreams(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            categoryId = "100",
        )

        assertTrue(result is SourceResult.Success)
        val movies = (result as SourceResult.Success).value
        assertEquals(2, movies.size)
        assertEquals(501, movies[0].streamId)
        assertEquals("mkv", movies[0].containerExtension)
        assertEquals(7.8, movies[0].rating!!, 0.001)
        assertEquals(null, movies[0].directSource)
        assertEquals(null, movies[1].containerExtension)

        val request = server.takeRequest()
        assertEquals("get_vod_streams", request.url.queryParameter("action"))
        assertEquals("100", request.url.queryParameter("category_id"))
    }

    @Test
    fun getVodInfo_mergesInfoAndMovieDataTolerantly() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "info": {
                        "name": "Movie One",
                        "o_name": "Original Movie One",
                        "description": "Provider description",
                        "cover_big": "https://images.example/cover.jpg",
                        "backdrop_path": [
                          "https://images.example/backdrop-1.jpg",
                          "https://images.example/backdrop-2.jpg"
                        ],
                        "releasedate": "2026-01-02",
                        "duration_secs": "6420",
                        "duration": "01:47:00",
                        "genre": "Drama",
                        "country": "AL",
                        "director": "Director",
                        "actors": "Actor One, Actor Two",
                        "rating": "8.1",
                        "youtube_trailer": "abc123"
                      },
                      "movie_data": {
                        "stream_id": 501,
                        "category_id": "100",
                        "container_extension": "mp4",
                        "direct_source": ""
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            vodId = 501,
        )

        assertTrue(result is SourceResult.Success)
        val info = (result as SourceResult.Success).value
        assertEquals(501, info.streamId)
        assertEquals("Movie One", info.name)
        assertEquals("Original Movie One", info.originalName)
        assertEquals(6420L, info.durationSeconds)
        assertEquals(2, info.backdropUrls.size)
        assertEquals("mp4", info.containerExtension)
        assertEquals(null, info.directSource)

        val request = server.takeRequest()
        assertEquals("get_vod_info", request.url.queryParameter("action"))
        assertEquals("501", request.url.queryParameter("vod_id"))
    }

    @Test
    fun getVodInfo_rejectsNonPositiveIdBeforeNetwork() = runBlocking {
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            vodId = 0,
        )

        assertEquals(SourceResult.Failure(SourceError.MalformedResponse), result)
        assertEquals(0, server.requestCount)
    }
}
