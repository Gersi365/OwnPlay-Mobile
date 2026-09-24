package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.M3uEntry
import app.ownplay.mobile.sources.data.m3u.M3uParseResult
import app.ownplay.mobile.sources.data.xtream.XtreamCategory
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStream
import app.ownplay.mobile.sources.data.xtream.XtreamMovie
import app.ownplay.mobile.sources.data.xtream.XtreamMovieDetail
import app.ownplay.mobile.sources.data.xtream.XtreamSeries
import app.ownplay.mobile.sources.data.xtream.XtreamSeriesDetail
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceCatalogLoaderDispatcherTest {
    @Test
    fun loadMovesCatalogWorkOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread().name
        var loaderThread: String? = null
        val m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult {
                loaderThread = Thread.currentThread().name
                return M3uParseResult(
                    entries = listOf(
                        M3uEntry(
                            name = "QA Channel",
                            groupTitle = "QA",
                            tvgId = "qa-channel",
                            tvgName = "QA Channel",
                            logoUrl = null,
                            streamUrl = "https://example.com/live.ts",
                        ),
                    ),
                    skippedEntries = 0,
                )
            }
        }
        val loader = DefaultSourceCatalogLoader(
            xtreamClient = UnsupportedXtreamClient,
            m3uClient = m3uClient,
        )

        val snapshot = loader.load(
            sourceId = SourceId("source-1"),
            sourceType = SourceType.M3U,
            baseLocator = "example.com",
            secret = SourceSecret.M3uRemote(
                playlistUrl = "https://example.com/list.m3u",
                epgUrl = null,
            ),
        )

        assertEquals(1, snapshot.liveChannels.size)
        assertNotEquals(callerThread, loaderThread)
    }

    @Test
    fun ungroupedM3uEntryDoesNotCreateSyntheticProviderCategory() = runBlocking {
        val m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult =
                M3uParseResult(
                    entries = listOf(
                        M3uEntry(
                            name = "Grouped",
                            groupTitle = "News",
                            tvgId = null,
                            tvgName = null,
                            logoUrl = null,
                            streamUrl = "https://example.com/grouped.ts",
                        ),
                        M3uEntry(
                            name = "Loose",
                            groupTitle = null,
                            tvgId = null,
                            tvgName = null,
                            logoUrl = null,
                            streamUrl = "https://example.com/loose.ts",
                        ),
                    ),
                    skippedEntries = 0,
                )
        }
        val loader = DefaultSourceCatalogLoader(
            xtreamClient = UnsupportedXtreamClient,
            m3uClient = m3uClient,
        )

        val snapshot = loader.load(
            sourceId = SourceId("source-1"),
            sourceType = SourceType.M3U,
            baseLocator = "example.com",
            secret = SourceSecret.M3uRemote(
                playlistUrl = "https://example.com/list.m3u",
                epgUrl = null,
            ),
        )

        assertEquals(listOf("News"), snapshot.categories.map { it.name })
        assertEquals(
            "News",
            snapshot.liveChannels.single { it.name == "Grouped" }.categoryProviderKey,
        )
        val loose = snapshot.liveChannels.single { it.name == "Loose" }
        assertEquals(null, loose.categoryProviderKey)
        assertEquals(
            StableIdentity.m3uLiveChannel(
                sourceId = SourceId("source-1"),
                tvgId = null,
                stableLocatorHint = "https://example.com/loose.ts",
                normalizedName = "Loose",
                normalizedGroup = "Other",
            ),
            loose.proposedChannelId,
        )
    }

    @Test
    fun collidingTokenStrippedLocatorsFallBackToStableMetadata() = runBlocking {
        val m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult =
                M3uParseResult(
                    entries = listOf(
                        M3uEntry(
                            name = "News One",
                            groupTitle = "News",
                            tvgId = null,
                            tvgName = "News One",
                            logoUrl = null,
                            streamUrl = "https://stream.example/live.ts?token=one",
                        ),
                        M3uEntry(
                            name = "News Two",
                            groupTitle = "News",
                            tvgId = null,
                            tvgName = "News Two",
                            logoUrl = null,
                            streamUrl = "https://stream.example/live.ts?token=two",
                        ),
                    ),
                    skippedEntries = 0,
                )
        }
        val loader = DefaultSourceCatalogLoader(
            xtreamClient = UnsupportedXtreamClient,
            m3uClient = m3uClient,
        )

        val snapshot = loader.load(
            sourceId = SourceId("source-1"),
            sourceType = SourceType.M3U,
            baseLocator = "example.com",
            secret = SourceSecret.M3uRemote(
                playlistUrl = "https://example.com/list.m3u",
                epgUrl = null,
            ),
        )

        assertEquals(2, snapshot.liveChannels.size)
        assertNotEquals(
            snapshot.liveChannels[0].proposedChannelId,
            snapshot.liveChannels[1].proposedChannelId,
        )
    }
}

private object UnsupportedXtreamClient : XtreamClient {
    override suspend fun liveCategories(connection: XtreamConnection): List<XtreamCategory> =
        error("Not used")

    override suspend fun liveStreams(connection: XtreamConnection): List<XtreamLiveStream> =
        error("Not used")

    override suspend fun movieCategories(connection: XtreamConnection): List<XtreamCategory> =
        error("Not used")

    override suspend fun movies(connection: XtreamConnection): List<XtreamMovie> =
        error("Not used")

    override suspend fun movieInfo(
        connection: XtreamConnection,
        movieId: String,
    ): XtreamMovieDetail = error("Not used")

    override suspend fun seriesCategories(connection: XtreamConnection): List<XtreamCategory> =
        error("Not used")

    override suspend fun series(connection: XtreamConnection): List<XtreamSeries> =
        error("Not used")

    override suspend fun seriesInfo(
        connection: XtreamConnection,
        seriesId: String,
    ): XtreamSeriesDetail = error("Not used")
}