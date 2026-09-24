package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.M3uParseResult
import app.ownplay.mobile.sources.data.xtream.XtreamCategory
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamClientException
import app.ownplay.mobile.sources.data.xtream.XtreamClientFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStream
import app.ownplay.mobile.sources.data.xtream.XtreamMovie
import app.ownplay.mobile.sources.data.xtream.XtreamSeries
import app.ownplay.mobile.sources.data.xtream.XtreamSeriesDetail
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCatalogLoaderPartialImportTest {
    private val sourceId = SourceId("partial-source")
    private val secret = SourceSecret.Xtream("user", "pass")

    @Test
    fun liveCatalogSurvivesIndependentVodFailure() = runBlocking {
        val snapshot = loader(PartialXtreamClient(failMovies = true)).load(
            sourceId, SourceType.XTREAM, "https://provider.example", secret,
        )

        assertEquals(listOf("42"), snapshot.liveChannels.map { it.providerStreamId })
        assertTrue(CatalogSection.LIVE_CATEGORIES in snapshot.authoritativeSections)
        assertTrue(CatalogSection.LIVE_CHANNELS in snapshot.authoritativeSections)
        assertFalse(CatalogSection.MOVIES in snapshot.authoritativeSections)
        assertTrue(snapshot.movies.isEmpty())
    }

    @Test
    fun authenticationFailureRemainsTerminalEvenWhenAnotherSectionResponds() = runBlocking {
        val error = runCatching {
            loader(PartialXtreamClient(authFailSeries = true)).load(
                sourceId, SourceType.XTREAM, "https://provider.example", secret,
            )
        }.exceptionOrNull() as CatalogLoadException

        assertEquals(SourceRefreshFailureCategory.AUTHENTICATION, error.category)
    }

    @Test
    fun refreshFailsWhenNoCoreCatalogSectionCanLoad() = runBlocking {
        val error = runCatching {
            loader(PartialXtreamClient(failAllCore = true)).load(
                sourceId, SourceType.XTREAM, "https://provider.example", secret,
            )
        }.exceptionOrNull() as CatalogLoadException

        assertEquals(SourceRefreshFailureCategory.PROVIDER, error.category)
    }

    private fun loader(client: XtreamClient) = DefaultSourceCatalogLoader(
        xtreamClient = client,
        m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult = error("unused")
        },
    )
}

private class PartialXtreamClient(
    private val failMovies: Boolean = false,
    private val authFailSeries: Boolean = false,
    private val failAllCore: Boolean = false,
) : XtreamClient {
    private fun failure(): Nothing = throw XtreamClientException(XtreamClientFailureCategory.PROVIDER)
    private fun <T> maybeFail(value: T): T = if (failAllCore) failure() else value

    override suspend fun liveCategories(connection: XtreamConnection): List<XtreamCategory> =
        maybeFail(listOf(XtreamCategory("7", "News", 0)))

    override suspend fun liveStreams(connection: XtreamConnection): List<XtreamLiveStream> =
        maybeFail(listOf(XtreamLiveStream("42", "7", "News", null, null, "ts", 0)))

    override suspend fun movieCategories(connection: XtreamConnection): List<XtreamCategory> =
        maybeFail(listOf(XtreamCategory("20", "Movies", 0)))

    override suspend fun movies(connection: XtreamConnection): List<XtreamMovie> {
        if (failAllCore || failMovies) failure()
        return emptyList()
    }

    override suspend fun seriesCategories(connection: XtreamConnection): List<XtreamCategory> =
        maybeFail(listOf(XtreamCategory("30", "Series", 0)))

    override suspend fun series(connection: XtreamConnection): List<XtreamSeries> {
        if (authFailSeries) throw XtreamClientException(XtreamClientFailureCategory.AUTHENTICATION)
        return maybeFail(emptyList())
    }

    override suspend fun seriesInfo(connection: XtreamConnection, seriesId: String) =
        XtreamSeriesDetail(emptyList())
}
