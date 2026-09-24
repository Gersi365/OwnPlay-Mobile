package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.flowOf

/** Exercises the real repository's state/metadata mapping without an Android database. */
internal class DownloadTestStore : DownloadDao {
    val rows = linkedMapOf<String, DownloadEntity>()
    private var time = 100L
    val repository = RoomDownloadRepository(this) { time++ }
    val request = DownloadRequest(SourceId("source-a"), DownloadMediaKind.MOVIE, "movie-a", "Movie")

    suspend fun completed(request: DownloadRequest = this.request): DownloadItem {
        val item = repository.enqueue(request)
        check(repository.markDownloading(item.downloadId))
        check(repository.complete(item.downloadId, "content://media/external/downloads/42", 4L, "a".repeat(64)))
        return requireNotNull(repository.get(item.downloadId))
    }

    override fun observeForSource(sourceId: String) = flowOf(rows.values.filter { it.sourceId == sourceId })
    override fun observe(downloadId: String) = flowOf(rows[downloadId])
    override suspend fun get(downloadId: String) = rows[downloadId]
    override suspend fun getExecutionQueue() =
        rows.values
            .filter {
                it.state == "WAITING_FOR_WIFI" ||
                    it.state == "QUEUED" ||
                    it.state == "DOWNLOADING"
            }
            .sortedWith(compareBy<DownloadEntity> { it.createdAt }.thenBy { it.downloadId })
    override suspend fun getIdsForSource(sourceId: String) =
        rows.values.filter { it.sourceId == sourceId }.sortedWith(
            compareBy<DownloadEntity> { it.createdAt }.thenBy { it.downloadId },
        ).map { it.downloadId }
    override suspend fun getForContent(sourceId: String, mediaKind: String, contentId: String) =
        rows.values.firstOrNull {
            it.sourceId == sourceId && it.mediaKind == mediaKind && it.contentId == contentId
        }
    override suspend fun upsert(entity: DownloadEntity) { rows[entity.downloadId] = entity }
    override suspend fun delete(downloadId: String) = if (rows.remove(downloadId) == null) 0 else 1
}
