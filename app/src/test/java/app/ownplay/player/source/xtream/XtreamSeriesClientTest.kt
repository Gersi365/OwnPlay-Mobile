package app.ownplay.player.source.xtream

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

class XtreamSeriesClientTest {
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
    fun getSeriesCategories_usesSeriesActionAndParsesCategories() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"category_id":"10","category_name":"Drama","parent_id":"0"},
                      {"category_id":"20","category_name":"Comedy"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamSeriesClient(allowCleartext = true)

        val result = client.getSeriesCategories(server.url("/").toString(), credentials)

        assertTrue(result is SourceResult.Success)
        val categories = (result as SourceResult.Success).value
        assertEquals(listOf("Drama", "Comedy"), categories.map { it.name })
        assertEquals("get_series_categories", server.takeRequest().url.queryParameter("action"))
    }

    @Test
    fun getSeries_parsesCatalogAndCategoryQuery() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {
                        "series_id": 501,
                        "name": "Series One",
                        "category_id": "10",
                        "cover": "https://images.example/series.jpg",
                        "rating": "8.4",
                        "last_modified": "1700000000",
                        "plot": "Provider plot"
                      }
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamSeriesClient(allowCleartext = true)

        val result = client.getSeries(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            categoryId = "10",
        )

        assertTrue(result is SourceResult.Success)
        val series = (result as SourceResult.Success).value.single()
        assertEquals(501, series.seriesId)
        assertEquals("Series One", series.name)
        assertEquals("10", series.categoryId)
        assertEquals(8.4, series.rating!!, 0.001)
        assertEquals(1700000000L, series.lastModifiedEpochSeconds)

        val request = server.takeRequest()
        assertEquals("get_series", request.url.queryParameter("action"))
        assertEquals("10", request.url.queryParameter("category_id"))
    }

    @Test
    fun getSeriesInfo_parsesSeasonsAndEpisodes() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "info": {
                        "name": "Series One",
                        "plot": "Series plot",
                        "cover": "https://images.example/series.jpg",
                        "backdrop_path": ["https://images.example/backdrop.jpg"],
                        "releaseDate": "2026-01-01",
                        "genre": "Drama",
                        "country": "AL",
                        "director": "Director",
                        "cast": "Actor One, Actor Two",
                        "rating": "8.5"
                      },
                      "seasons": [
                        {
                          "season_number": 1,
                          "name": "Season 1",
                          "air_date": "2026-01-01",
                          "cover": "https://images.example/season.jpg"
                        }
                      ],
                      "episodes": {
                        "1": [
                          {
                            "id": "1001",
                            "episode_num": 1,
                            "season": 1,
                            "title": "Pilot",
                            "container_extension": "mkv",
                            "added": "1700000000",
                            "info": {
                              "duration_secs": "3600",
                              "plot": "Episode plot",
                              "movie_image": "https://images.example/episode.jpg",
                              "rating": "8.2"
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamSeriesClient(allowCleartext = true)

        val result = client.getSeriesInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            seriesId = 501,
        )

        assertTrue(result is SourceResult.Success)
        val info = (result as SourceResult.Success).value
        assertEquals("Series One", info.name)
        assertEquals(1, info.seasons.size)
        assertEquals(1, info.seasons.single().seasonNumber)
        assertEquals(1, info.episodes.size)
        val episode = info.episodes.single()
        assertEquals(1001, episode.episodeId)
        assertEquals(1, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
        assertEquals("Pilot", episode.title)
        assertEquals("mkv", episode.containerExtension)
        assertEquals(3600L, episode.durationSeconds)

        val request = server.takeRequest()
        assertEquals("get_series_info", request.url.queryParameter("action"))
        assertEquals("501", request.url.queryParameter("series_id"))
    }
}
