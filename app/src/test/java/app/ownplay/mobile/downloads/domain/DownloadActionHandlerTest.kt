package app.ownplay.mobile.downloads.domain

import app.ownplay.mobile.downloads.data.DownloadTestStore
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionHandlerTest {
    private val store = DownloadTestStore()
    private val handler = DownloadActionHandler(store.repository)

    @Test
    fun downloadPauseResumeAndRetryUseRepositoryTransitions() = runBlocking {
        assertTrue(handler.execute(DownloadUserAction.DOWNLOAD, store.request, null))
        val id = DownloadId(store.rows.values.single().downloadId)
        assertTrue(handler.execute(DownloadUserAction.PAUSE, store.request, id))
        assertEquals(DownloadStatus.PAUSED, store.repository.get(id)?.status)
        assertTrue(handler.execute(DownloadUserAction.RESUME, store.request, id))
        assertEquals(DownloadStatus.QUEUED, store.repository.get(id)?.status)
        assertTrue(store.repository.markDownloading(id))
        assertTrue(store.repository.fail(id, DownloadFailureCode.NETWORK))
        assertTrue(handler.execute(DownloadUserAction.RETRY, store.request, id))
        assertEquals(DownloadStatus.QUEUED, store.repository.get(id)?.status)
    }

    @Test
    fun staleRetryOrDownloadCannotResumeAPausedItem() = runBlocking {
        val item = store.repository.enqueue(store.request)
        store.repository.pause(item.downloadId)
        assertFalse(handler.execute(DownloadUserAction.RETRY, store.request, item.downloadId))
        assertFalse(handler.execute(DownloadUserAction.DOWNLOAD, store.request, null))
        assertEquals(DownloadStatus.PAUSED, store.repository.get(item.downloadId)?.status)
    }

    @Test
    fun staleRemoveCannotDeleteAResumedTransfer() = runBlocking {
        val item = store.repository.enqueue(store.request)
        store.repository.pause(item.downloadId)
        store.repository.resume(item.downloadId)
        assertFalse(handler.execute(DownloadUserAction.REMOVE, store.request, item.downloadId))
        assertEquals(DownloadStatus.QUEUED, store.repository.get(item.downloadId)?.status)
    }

    @Test
    fun mismatchedOrRemovedRecordsCannotBeMutated() = runBlocking {
        val item = store.repository.enqueue(store.request)
        val otherRequests = listOf(
            store.request.copy(sourceId = SourceId("source-b")),
            store.request.copy(mediaKind = DownloadMediaKind.EPISODE),
            store.request.copy(contentId = "other"),
        )
        otherRequests.forEach { assertFalse(handler.execute(DownloadUserAction.PAUSE, it, item.downloadId)) }
        assertEquals(DownloadStatus.QUEUED, store.repository.get(item.downloadId)?.status)
        store.rows.clear()
        assertFalse(handler.execute(DownloadUserAction.RETRY, store.request, item.downloadId))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun offlinePlaybackIsNotADownloadMutationAndRemovalIsExplicit() = runBlocking {
        val item = store.completed()
        assertFalse(handler.execute(DownloadUserAction.PLAY_OFFLINE, store.request, item.downloadId))
        assertEquals(item, store.repository.get(item.downloadId))
        assertTrue(handler.execute(DownloadUserAction.REMOVE, store.request, item.downloadId))
        assertNull(store.repository.get(item.downloadId))
    }
}
