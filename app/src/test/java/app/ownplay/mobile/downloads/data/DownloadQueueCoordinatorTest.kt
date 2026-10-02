package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueCoordinatorTest {
    @Test
    fun waitingForWifiKeepsItsFifoPositionWhenYoungerWorkerWakesFirst() = runBlocking {
        val store = DownloadTestStore()
        val older = store.repository.enqueue(
            DownloadRequest(
                sourceId = store.request.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = "older",
                title = "Older",
            ),
        )
        val middle = store.repository.enqueue(
            DownloadRequest(
                sourceId = store.request.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = "middle",
                title = "Middle",
            ),
        )
        val younger = store.repository.enqueue(
            DownloadRequest(
                sourceId = store.request.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = "younger",
                title = "Younger",
            ),
        )
        val newest = store.repository.enqueue(
            DownloadRequest(
                sourceId = store.request.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = "newest",
                title = "Newest",
            ),
        )
        assertTrue(store.repository.markWaitingForWifi(older.downloadId))
        assertTrue(store.repository.markWaitingForWifi(middle.downloadId))
        assertTrue(store.repository.markWaitingForWifi(younger.downloadId))

        val coordinator = DownloadQueueCoordinator(store, store.repository)
        assertFalse(coordinator.tryAdmit(newest.downloadId))

        assertTrue(store.repository.markQueued(older.downloadId))
        assertTrue(coordinator.tryAdmit(older.downloadId))
        assertTrue(store.repository.markQueued(middle.downloadId))
        assertTrue(coordinator.tryAdmit(middle.downloadId))
        assertTrue(store.repository.markQueued(younger.downloadId))
        assertTrue(coordinator.tryAdmit(younger.downloadId))
        assertFalse(coordinator.tryAdmit(newest.downloadId))
    }

    @Test
    fun persistedDownloadingRowIsReadmittedAfterWorkerRecreation() = runBlocking {
        val store = DownloadTestStore()
        val item = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(item.downloadId))

        val recreatedCoordinator = DownloadQueueCoordinator(store, store.repository)

        assertTrue(recreatedCoordinator.tryAdmit(item.downloadId))
        assertEquals(DownloadStatus.DOWNLOADING, store.repository.get(item.downloadId)?.status)
    }

    @Test
    fun admitsOnlyTheOldestThreeQueuedDownloads() = runBlocking {
        val store = DownloadTestStore()
        val requests = (1..5).map { index ->
            DownloadRequest(
                sourceId = store.request.sourceId,
                mediaKind = DownloadMediaKind.MOVIE,
                contentId = "movie-$index",
                title = "Movie $index",
            )
        }
        val items = requests.map { store.repository.enqueue(it) }
        val coordinator = DownloadQueueCoordinator(store, store.repository)

        assertFalse(coordinator.tryAdmit(items[3].downloadId))
        assertTrue(coordinator.tryAdmit(items[0].downloadId))
        assertTrue(coordinator.tryAdmit(items[1].downloadId))
        assertTrue(coordinator.tryAdmit(items[2].downloadId))
        assertFalse(coordinator.tryAdmit(items[3].downloadId))
        assertFalse(coordinator.tryAdmit(items[4].downloadId))
        assertEquals(
            3,
            store.rows.values.count { it.state == DownloadStatus.DOWNLOADING.name },
        )

        assertTrue(store.repository.fail(items[0].downloadId, DownloadFailureCode.NETWORK))
        assertTrue(coordinator.tryAdmit(items[3].downloadId))
        assertFalse(coordinator.tryAdmit(items[4].downloadId))
    }
}
