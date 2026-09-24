package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.sources.data.xtream.XtreamSeriesEpisode
import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibrarySeriesEpisodeCacheMapperTest {
    @Test
    fun providerEpisodeIdentityDrivesStableLocalIdentity() {
        val first = LibrarySeriesEpisodeCacheMapper.rows(
            sourceId = SourceId("source-a"),
            seriesId = "series-local",
            generation = 7L,
            episodes = listOf(episode("1001", season = 1, number = 1)),
        ).single()
        val sameProviderEpisode = LibrarySeriesEpisodeCacheMapper.rows(
            sourceId = SourceId("source-a"),
            seriesId = "series-local",
            generation = 8L,
            episodes = listOf(episode("1001", season = 9, number = 4)),
        ).single()
        val otherProviderEpisode = LibrarySeriesEpisodeCacheMapper.rows(
            sourceId = SourceId("source-a"),
            seriesId = "series-local",
            generation = 8L,
            episodes = listOf(episode("1002", season = 1, number = 1)),
        ).single()

        assertEquals(first.episodeId, sameProviderEpisode.episodeId)
        assertNotEquals(first.episodeId, otherProviderEpisode.episodeId)
        assertEquals("1001", first.providerEpisodeId)
        assertEquals("xtream://episode/1001", first.streamLocator)
        assertFalse(first.streamLocator.contains("password"))
    }

    @Test
    fun mapperPreservesProviderSeasonEpisodeExtensionAndGeneration() {
        val row = LibrarySeriesEpisodeCacheMapper.rows(
            sourceId = SourceId("source-a"),
            seriesId = "series-local",
            generation = 11L,
            episodes = listOf(episode("1001", season = 2, number = 7)),
        ).single()

        assertEquals(2, row.seasonNumber)
        assertEquals(7, row.episodeNumber)
        assertEquals("Episode", row.title)
        assertEquals(2_700_000L, row.durationMs)
        assertEquals("mkv", row.extension)
        assertEquals(11L, row.lastSeenGeneration)
    }

    private fun episode(
        id: String,
        season: Int,
        number: Int,
    ) = XtreamSeriesEpisode(
        providerEpisodeId = id,
        seasonNumber = season,
        episodeNumber = number,
        title = "Episode",
        containerExtension = "mkv",
        durationMs = 2_700_000L,
    )
}
