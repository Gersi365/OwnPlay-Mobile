package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.M3uParseResult
import app.ownplay.mobile.sources.data.xtream.XtreamAccountInfo
import app.ownplay.mobile.sources.data.xtream.XtreamCategory
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStream
import app.ownplay.mobile.sources.data.xtream.XtreamMovie
import app.ownplay.mobile.sources.data.xtream.XtreamSeries
import app.ownplay.mobile.sources.data.xtream.XtreamSeriesDetail
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCatalogLoaderXtreamFormatTest {
    @Test
    fun accountOutputFormatFillsMissingLiveContainerExtension() = runBlocking {
        val snapshot = loader(
            accountFormats = listOf("m3u8", "ts"),
            streamExtension = null,
        ).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertEquals("xtream://live/42?ext=m3u8", snapshot.liveChannels.single().streamLocator)
    }

    @Test
    fun streamContainerExtensionOverridesAccountOutputFormat() = runBlocking {
        val snapshot = loader(
            accountFormats = listOf("m3u8"),
            streamExtension = "ts",
        ).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertEquals("xtream://live/42?ext=ts", snapshot.liveChannels.single().streamLocator)
    }

    private fun loader(
        accountFormats: List<String>,
        streamExtension: String?,
    ) = DefaultSourceCatalogLoader(
        xtreamClient = FakeXtreamClient(accountFormats, streamExtension),
        m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult = error("Not used")
        },
    )

    private companion object {
        val sourceId = SourceId("source-1")
        val secret = SourceSecret.Xtream("user", "pass")
    }
}

private class FakeXtreamClient(
    private val accountFormats: List<String>,
    private val streamExtension: String?,
) : XtreamClient {
    override suspend fun accountInfo(connection: XtreamConnection) = XtreamAccountInfo(accountFormats)
    override suspend fun liveCategories(connection: XtreamConnection) =
        listOf(XtreamCategory("7", "News", 0))
    override suspend fun liveStreams(connection: XtreamConnection) =
        listOf(XtreamLiveStream("42", "7", "News", null, null, streamExtension, 0))
    override suspend fun movieCategories(connection: XtreamConnection): List<XtreamCategory> = emptyList()
    override suspend fun movies(connection: XtreamConnection): List<XtreamMovie> = emptyList()
    override suspend fun seriesCategories(connection: XtreamConnection): List<XtreamCategory> = emptyList()
    override suspend fun series(connection: XtreamConnection): List<XtreamSeries> = emptyList()
    override suspend fun seriesInfo(connection: XtreamConnection, seriesId: String) = XtreamSeriesDetail(emptyList())
}
