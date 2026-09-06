package app.ownplay.player.live.ingest

import app.ownplay.player.source.m3u.M3uEntry
import app.ownplay.player.source.m3u.M3uPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uDuplicateIdentityTest {
    @Test
    fun duplicateTvgIdWithDifferentStreamsGetsStableDistinctProviderKeys() {
        val playlist = M3uPlaylist(
            entries = listOf(
                M3uEntry(
                    displayName = "News HD",
                    streamUrl = "https://cdn-a.example/live/news.m3u8?token=first",
                    tvgId = "news.example",
                    groupTitle = "News",
                ),
                M3uEntry(
                    displayName = "News FHD",
                    streamUrl = "https://cdn-b.example/live/news.m3u8?token=second",
                    tvgId = "news.example",
                    groupTitle = "News",
                ),
            ),
        )

        val first = InitialLiveCatalogFactory.fromM3u(playlist)
        val refreshed = InitialLiveCatalogFactory.fromM3u(
            playlist.copy(
                entries = listOf(
                    playlist.entries[0].copy(
                        streamUrl = "https://cdn-a.example/live/news.m3u8?token=rotated-a",
                    ),
                    playlist.entries[1].copy(
                        streamUrl = "https://cdn-b.example/live/news.m3u8?token=rotated-b",
                    ),
                ),
            ),
        )

        assertEquals(2, first.channels.size)
        assertNotEquals(first.channels[0].providerKey, first.channels[1].providerKey)
        assertEquals(
            first.channels.map { it.providerKey },
            refreshed.channels.map { it.providerKey },
        )
        assertTrue(first.channels.all { it.tvgId == "news.example" })
    }

    @Test
    fun exactDuplicateRowsAreCollapsedInsteadOfFailingImport() {
        val entry = M3uEntry(
            displayName = "News",
            streamUrl = "https://cdn.example/live/news.m3u8",
            tvgId = "news.example",
            groupTitle = "News",
        )

        val catalog = InitialLiveCatalogFactory.fromM3u(
            M3uPlaylist(entries = listOf(entry, entry)),
        )

        assertEquals(1, catalog.channels.size)
    }
}
