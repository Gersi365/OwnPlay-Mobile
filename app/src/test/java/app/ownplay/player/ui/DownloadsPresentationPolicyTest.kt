package app.ownplay.player.ui

import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsPresentationPolicyTest {
    @Test
    fun progressAndQueuePromotionKeepActivePositions() {
        val first = download("new", created = 200, state = DownloadStates.QUEUED)
        val second = download("old", created = 100, state = DownloadStates.DOWNLOADING)
        val before = DownloadsPresentationPolicy.items(listOf(second, first))
        val after = DownloadsPresentationPolicy.items(
            listOf(
                first.copy(state = DownloadStates.DOWNLOADING, bytesDownloaded = 50, updatedAtEpochMillis = 900),
                second.copy(bytesDownloaded = 99, totalBytes = 100, updatedAtEpochMillis = 999),
            ),
        )
        assertEquals(listOf("new", "old"), mediaIds(before))
        assertEquals(before.map { it.key }, after.map { it.key })
    }

    @Test
    fun stateTransitionsMoveOnlyToTheAppropriateSection() {
        val active = download("a", state = DownloadStates.DOWNLOADING)
        val paused = download("p", state = DownloadStates.PAUSED)
        val failed = download("f", state = DownloadStates.FAILED)
        val completed = download("c", state = DownloadStates.COMPLETED)
        val items = DownloadsPresentationPolicy.items(listOf(completed, failed, paused, active))
        assertEquals(DownloadsSection.entries, items.filterIsInstance<DownloadsListItem.Section>().map { it.section })
        assertEquals(listOf("a", "p", "f", "c"), mediaIds(items))
        val transitioned = DownloadsPresentationPolicy.items(listOf(active.copy(state = DownloadStates.PAUSED), paused))
        assertEquals(
            listOf(DownloadsListItem.Section(DownloadsSection.PAUSED, 2)),
            transitioned.filterIsInstance<DownloadsListItem.Section>(),
        )
        assertEquals(listOf("a", "p"), mediaIds(transitioned))
    }

    @Test
    fun equalCreationTimesUseIdentityAndUnknownStatesRemainVisible() {
        val items = DownloadsPresentationPolicy.items(listOf(download("z"), download("a"), download("unknown", state = "UNRECOGNIZED")))
        assertEquals(listOf("a", "z", "unknown"), mediaIds(items))
        assertEquals(DownloadsSection.NEEDS_ATTENTION, items.filterIsInstance<DownloadsListItem.Section>().last().section)
    }

    @Test
    fun completedMediaHasMovieSeriesSeasonAndEpisodeHierarchy() {
        val items = DownloadsPresentationPolicy.items(
            listOf(
                episode("s2e1", season = 2, episode = 1),
                episode("s1e2", season = 1, episode = 2),
                download("movie", state = DownloadStates.COMPLETED),
                episode("s1e1", season = 1, episode = 1),
                episode("unknown-season", season = null, episode = null),
            ),
        )
        assertEquals(listOf("movie", "s1e1", "s1e2", "s2e1", "unknown-season"), mediaIds(items))
        assertEquals(
            listOf("Movies", "Example series", "Season 01", "Season 02", "Other episodes"),
            items.filterIsInstance<DownloadsListItem.Group>().map { it.title },
        )
        assertEquals(DownloadsListItem.Section(DownloadsSection.DOWNLOADED, 5), items.first())
    }

    @Test
    fun providersAndMissingSeriesMetadataDoNotMergeAndLazyKeysAreUnique() {
        val rows = listOf(
            episode("provider-a", 1, 1),
            episode("provider-b", 1, 1).copy(sourceId = "another-source"),
            episode("missing-1", 1, 1).copy(seriesTitle = null),
            episode("missing-2", 1, 1).copy(seriesTitle = " "),
            episode("delimiter-title", 1, 1).copy(seriesTitle = "title:Example:series"),
        )
        val items = DownloadsPresentationPolicy.items(rows)
        assertEquals(rows.size, items.filterIsInstance<DownloadsListItem.Group>().count { !it.isSeason })
        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertEquals(rows.map { it.downloadId }.toSet(), mediaIds(items).toSet())
    }

    @Test
    fun completedGroupsAndRowsIgnoreUpdatesAndIncomingOrder() {
        val rows = listOf(
            episode("first", 1, 2).copy(createdAtEpochMillis = 200),
            episode("second", 1, 1).copy(createdAtEpochMillis = 100),
            episode("third", 1, 1).copy(seriesTitle = "Another series"),
            download("movie", state = DownloadStates.COMPLETED),
        )
        val before = DownloadsPresentationPolicy.items(rows)
        val after = DownloadsPresentationPolicy.items(rows.reversed().map {
            it.copy(bytesDownloaded = 1000, updatedAtEpochMillis = 9999)
        })
        assertEquals(before.map { it.key }, after.map { it.key })
    }

    @Test
    fun emptyDownloadsDoNotCreateEmptySections() {
        assertTrue(DownloadsPresentationPolicy.items(emptyList()).isEmpty())
    }

    private fun mediaIds(items: List<DownloadsListItem>): List<String> =
        items.filterIsInstance<DownloadsListItem.Media>().map { it.download.downloadId }

    private fun episode(id: String, season: Int?, episode: Int?): OfflineDownload =
        download(id, state = DownloadStates.COMPLETED).copy(
            mediaKind = DownloadMediaKinds.SERIES_EPISODE,
            seriesTitle = "Example series",
            seasonNumber = season,
            episodeNumber = episode,
        )

    private fun download(id: String, created: Long = 100, state: String = DownloadStates.DOWNLOADING) = OfflineDownload(
        downloadId = id,
        sourceId = "source",
        mediaKind = DownloadMediaKinds.MOVIE,
        contentId = "content:$id",
        title = id,
        seriesTitle = null,
        seasonNumber = null,
        episodeNumber = null,
        posterUrl = null,
        state = state,
        bytesDownloaded = 0,
        totalBytes = null,
        failureReason = null,
        createdAtEpochMillis = created,
        updatedAtEpochMillis = created,
    )
}
