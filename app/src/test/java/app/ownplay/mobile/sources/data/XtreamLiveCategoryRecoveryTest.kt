package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.M3uParseResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamLiveCategoryRecoveryTest {
    private val sourceId = SourceId("category-recovery")
    private val secret = SourceSecret.Xtream("user", "pass")

    @Test
    fun validGlobalCategoryIdsDoNotTriggerScopedRecovery() = runBlocking {
        val client = RecoveryClient(globalCategoryId = "7")
        val snapshot = loader(client).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertEquals("7", snapshot.liveChannels.single().categoryProviderKey)
        assertTrue(client.scopedCalls.isEmpty())
    }

    @Test
    fun missingGlobalCategoryIsRecoveredOnlyFromUniqueScopedMembership() = runBlocking {
        val client = RecoveryClient(
            globalCategoryId = null,
            scopedMembership = mapOf("7" to setOf("42"), "8" to emptySet()),
        )
        val snapshot = loader(client).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertEquals("7", snapshot.liveChannels.single().categoryProviderKey)
        assertEquals(setOf("7", "8"), client.scopedCalls.toSet())
    }

    @Test
    fun ambiguousScopedMembershipDoesNotInventProviderCategory() = runBlocking {
        val client = RecoveryClient(
            globalCategoryId = null,
            scopedMembership = mapOf("7" to setOf("42"), "8" to setOf("42")),
        )
        val snapshot = loader(client).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertNull(snapshot.liveChannels.single().categoryProviderKey)
    }

    @Test
    fun scopedRecoveryFailureDoesNotInvalidateSuccessfulGlobalLiveCatalog() = runBlocking {
        val client = RecoveryClient(globalCategoryId = null, failScopedCategory = "7")
        val snapshot = loader(client).load(sourceId, SourceType.XTREAM, "https://provider.example", secret)

        assertEquals(listOf("42"), snapshot.liveChannels.map { it.providerStreamId })
        assertNull(snapshot.liveChannels.single().categoryProviderKey)
        assertTrue(CatalogSection.LIVE_CHANNELS in snapshot.authoritativeSections)
    }

    private fun loader(client: XtreamClient) = DefaultSourceCatalogLoader(
        xtreamClient = client,
        m3uClient = object : M3uClient {
            override suspend fun fetch(remoteUrl: String): M3uParseResult = error("unused")
        },
    )
}

private class RecoveryClient(
    private val globalCategoryId: String?,
    private val scopedMembership: Map<String, Set<String>> = emptyMap(),
    private val failScopedCategory: String? = null,
) : XtreamClient {
    val scopedCalls = mutableListOf<String>()

    override suspend fun liveCategories(connection: XtreamConnection) = listOf(
        XtreamCategory("7", "News", 0),
        XtreamCategory("8", "Sports", 1),
    )

    override suspend fun liveStreams(connection: XtreamConnection) =
        listOf(stream("42", globalCategoryId, 0))

    override suspend fun liveStreams(connection: XtreamConnection, categoryId: String): List<XtreamLiveStream> {
        synchronized(scopedCalls) { scopedCalls += categoryId }
        if (categoryId == failScopedCategory) error("scoped endpoint failed")
        return scopedMembership[categoryId].orEmpty().mapIndexed { index, streamId ->
            stream(streamId, categoryId, index)
        }
    }

    override suspend fun movieCategories(connection: XtreamConnection): List<XtreamCategory> = emptyList()
    override suspend fun movies(connection: XtreamConnection): List<XtreamMovie> = emptyList()
    override suspend fun seriesCategories(connection: XtreamConnection): List<XtreamCategory> = emptyList()
    override suspend fun series(connection: XtreamConnection): List<XtreamSeries> = emptyList()
    override suspend fun seriesInfo(connection: XtreamConnection, seriesId: String) = XtreamSeriesDetail(emptyList())

    private fun stream(id: String, categoryId: String?, order: Int) =
        XtreamLiveStream(id, categoryId, "Channel $id", null, null, "ts", order)
}
