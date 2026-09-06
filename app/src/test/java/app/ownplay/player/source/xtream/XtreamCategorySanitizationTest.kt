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

class XtreamCategorySanitizationTest {
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
    fun vodSkipsBlankCategoryDefinitionsAndClearsBlankReferences() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"category_id":"   ","category_name":"Invalid"},
                      {"category_id":"10","category_name":"Movies"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("""[{"stream_id":42,"name":"Movie","category_id":"   "}]""")
                .build(),
        )
        val client = XtreamClient(allowCleartext = true)

        val categoriesResult = client.getVodCategories(server.url("/").toString(), credentials)
        assertTrue(categoriesResult is SourceResult.Success)
        assertEquals(
            listOf("10"),
            (categoriesResult as SourceResult.Success).value.map(XtreamCategory::id),
        )

        val moviesResult = client.getVodStreams(server.url("/").toString(), credentials)
        assertTrue(moviesResult is SourceResult.Success)
        assertNull((moviesResult as SourceResult.Success).value.single().categoryId)
    }

    @Test
    fun seriesSkipsBlankCategoryDefinitionsAndClearsBlankReferences() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(
                    """
                    [
                      {"category_id":"","category_name":"Invalid"},
                      {"category_id":"20","category_name":"Drama"}
                    ]
                    """.trimIndent(),
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .body("""[{"series_id":501,"name":"Series","category_id":" "}]""")
                .build(),
        )
        val client = XtreamSeriesClient(allowCleartext = true)

        val categoriesResult = client.getSeriesCategories(server.url("/").toString(), credentials)
        assertTrue(categoriesResult is SourceResult.Success)
        assertEquals(
            listOf("20"),
            (categoriesResult as SourceResult.Success).value.map(XtreamCategory::id),
        )

        val seriesResult = client.getSeries(server.url("/").toString(), credentials)
        assertTrue(seriesResult is SourceResult.Success)
        assertNull((seriesResult as SourceResult.Success).value.single().categoryId)
    }
}
