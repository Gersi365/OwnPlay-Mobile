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

class XtreamInvalidMediaIdTest {
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
    fun vodCatalogSkipsNonPositiveStreamIds() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"stream_id":0,"name":"Invalid Zero"},
                      {"stream_id":-2,"name":"Invalid Negative"},
                      {"stream_id":42,"name":"Valid Movie"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val result = client.getVodStreams(server.url("/").toString(), credentials)

        assertTrue(result is SourceResult.Success)
        val movies = (result as SourceResult.Success).value
        assertEquals(listOf(42), movies.map(XtreamVodStream::streamId))
        assertEquals(listOf("Valid Movie"), movies.map(XtreamVodStream::name))
    }

    @Test
    fun seriesCatalogAndEpisodesSkipNonPositiveIds() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"series_id":0,"name":"Invalid Series"},
                      {"series_id":501,"name":"Valid Series"}
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
                      "info":{"name":"Valid Series"},
                      "episodes":{
                        "1":[
                          {"id":0,"episode_num":1,"season":1,"title":"Invalid Episode"},
                          {"id":1001,"episode_num":2,"season":1,"title":"Valid Episode"}
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
        assertEquals(listOf(501), series.map(XtreamSeriesSummary::seriesId))

        val infoResult = client.getSeriesInfo(
            serverUrl = server.url("/").toString(),
            credentials = credentials,
            seriesId = 501,
        )
        assertTrue(infoResult is SourceResult.Success)
        val episodes = (infoResult as SourceResult.Success).value.episodes
        assertEquals(listOf(1001), episodes.map(XtreamSeriesEpisode::episodeId))
        assertEquals(listOf("Valid Episode"), episodes.map(XtreamSeriesEpisode::title))
    }

    @Test
    fun seriesInfoRejectsInvalidNumberingButKeepsSpecialsSeasonZero() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    {
                      "info":{"name":"Series"},
                      "seasons":[
                        {"season_number":-1,"name":"Invalid"},
                        {"season_number":0,"name":"Specials"}
                      ],
                      "episodes":{
                        "-1":[
                          {"id":2001,"episode_num":1,"title":"Invalid season"}
                        ],
                        "0":[
                          {"id":2002,"episode_num":0,"title":"Invalid episode number"},
                          {"id":2003,"episode_num":1,"title":"Valid special"}
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
        assertEquals(listOf(0), info.seasons.map(XtreamSeriesSeason::seasonNumber))
        assertEquals(listOf(2003), info.episodes.map(XtreamSeriesEpisode::episodeId))
        assertEquals(listOf(0), info.episodes.map(XtreamSeriesEpisode::seasonNumber))
        assertEquals(listOf(1), info.episodes.map(XtreamSeriesEpisode::episodeNumber))
    }
}
