package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.downloads.data.DownloadedMediaVerifier
import app.ownplay.mobile.downloads.data.DownloadTestStore
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.feature.playback.domain.LibraryPlaybackMediaResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.sources.domain.SourceId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAwareLibraryPlaybackResolverTest {
    private val store = DownloadTestStore()
    private val onlineTargets = mutableListOf<PlaybackTarget.Library>()
    private val online = object : LibraryPlaybackMediaResolver {
        override suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia {
            onlineTargets += target
            return PreparedPlaybackMedia("https://provider.example/movie")
        }
    }
    private fun resolver(verify: suspend () -> Boolean = { true }) = DownloadAwareLibraryPlaybackResolver(
        online, store.repository, DownloadedMediaVerifier { verify() },
    )

    @Test
    fun ordinaryPlayPrefersAValidCompletedDownload() = runBlocking {
        val item = store.completed()
        val target = PlaybackTarget.Movie(store.request.sourceId, store.request.contentId)
        val media = resolver().resolve(target)
        assertEquals(item.localReference, media?.uri)
        assertTrue(onlineTargets.isEmpty())
    }

    @Test
    fun ordinaryPlayUsesOnlineMediaWithoutCancellingAnActiveDownload() = runBlocking {
        val item = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(item.downloadId))
        val target = PlaybackTarget.Movie(store.request.sourceId, store.request.contentId)

        val media = resolver().resolve(target)

        assertEquals("https://provider.example/movie", media?.uri)
        assertEquals(DownloadStatus.DOWNLOADING, store.repository.get(item.downloadId)?.status)
        assertEquals(listOf(target), onlineTargets)
    }

    @Test
    fun completedMovieAndEpisodeResolveOnlyTheirLocalReference() = runBlocking {
        for (kind in DownloadMediaKind.entries) {
            val item = store.completed(store.request.copy(mediaKind = kind))
            val target = when (kind) {
                DownloadMediaKind.MOVIE -> PlaybackTarget.Movie(item.sourceId, item.contentId, item.downloadId.value)
                DownloadMediaKind.EPISODE -> PlaybackTarget.Episode(item.sourceId, item.contentId, item.downloadId.value)
            }
            assertEquals(item.localReference, resolver().resolve(target)?.uri)
        }
        assertTrue(onlineTargets.isEmpty())
    }

    @Test
    fun mismatchedIdentityNeverOpensAnotherSourcesOrKindsFile() = runBlocking {
        val item = store.completed()
        val candidates = listOf(
            PlaybackTarget.Movie(SourceId("source-b"), item.contentId, item.downloadId.value),
            PlaybackTarget.Movie(item.sourceId, "other-movie", item.downloadId.value),
            PlaybackTarget.Episode(item.sourceId, item.contentId, item.downloadId.value),
            PlaybackTarget.Movie(item.sourceId, item.contentId, "missing-download"),
        )
        val resolver = resolver { error("Invalid identity must be rejected before file access") }
        candidates.forEach { assertNull(resolver.resolve(it)) }
        assertTrue(onlineTargets.isEmpty())
    }

    @Test
    fun incompleteOrInconsistentMetadataCannotFallBackToNetwork() = runBlocking {
        val item = store.completed()
        val target = PlaybackTarget.Movie(item.sourceId, item.contentId, item.downloadId.value)
        val row = store.rows.getValue(item.downloadId.value)
        val invalid = listOf(
            row.copy(state = "PAUSED"),
            row.copy(localReference = null),
            row.copy(bytesDownloaded = 3L),
            row.copy(totalBytes = 5L),
            row.copy(integrityMetadata = null),
            row.copy(integrityMetadata = "bytes=4;sha256=broken"),
        )
        val resolver = resolver { error("Invalid metadata must not reach file verification") }
        invalid.forEach {
            store.rows[item.downloadId.value] = it
            assertNull(resolver.resolve(target))
        }
        assertTrue(onlineTargets.isEmpty())
    }

    @Test
    fun damagedExplicitOfflineFileBecomesMissingWithoutNetworkFallback() = runBlocking {
        val item = store.completed()
        val target = PlaybackTarget.Movie(item.sourceId, item.contentId, item.downloadId.value)
        assertNull(resolver { false }.resolve(target))
        assertEquals(DownloadStatus.MISSING, store.repository.get(item.downloadId)?.status)
        assertTrue(onlineTargets.isEmpty())
    }

    @Test
    fun damagedPreferredLocalFileFallsBackToOnlineAndBecomesMissing() = runBlocking {
        val item = store.completed()
        val target = PlaybackTarget.Movie(item.sourceId, item.contentId)
        val media = resolver { throw IOException("unreadable") }.resolve(target)
        assertEquals("https://provider.example/movie", media?.uri)
        assertEquals(DownloadStatus.MISSING, store.repository.get(item.downloadId)?.status)
        assertEquals(listOf(target), onlineTargets)
    }

    @Test
    fun removalDuringVerificationPreventsPlayback() = runBlocking {
        val item = store.completed()
        val target = PlaybackTarget.Movie(item.sourceId, item.contentId, item.downloadId.value)
        assertNull(resolver { store.rows.clear(); true }.resolve(target))
        assertTrue(onlineTargets.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun verificationCancellationPropagates() = runBlocking {
        val item = store.completed()
        resolver { throw CancellationException("cancelled") }
            .resolve(PlaybackTarget.Movie(item.sourceId, item.contentId, item.downloadId.value))
        Unit
    }
}
