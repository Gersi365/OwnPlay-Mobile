package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadIntegrityPolicy
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadProgressPolicy
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStateTransitionPolicy
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDownloadRepository(
    private val dao: DownloadDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadRepository {
    override fun observeDownloads(sourceId: SourceId): Flow<List<DownloadItem>> =
        dao.observeForSource(sourceId.value).map { rows -> rows.map(::toDomain) }

    override fun observeDownload(downloadId: DownloadId): Flow<DownloadItem?> =
        dao.observe(downloadId.value).map { row -> row?.let(::toDomain) }

    override suspend fun get(downloadId: DownloadId): DownloadItem? =
        dao.get(downloadId.value)?.let(::toDomain)

    override suspend fun enqueue(request: DownloadRequest): DownloadItem {
        val existing = dao.getForContent(
            sourceId = request.sourceId.value,
            mediaKind = request.mediaKind.name,
            contentId = request.contentId,
        )
        if (existing != null) {
            val status = persistedStatus(existing.state)
            if (status in setOf(
                    DownloadStatus.WAITING_FOR_WIFI,
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.PAUSED,
                    DownloadStatus.COMPLETED,
                )
            ) {
                return toDomain(existing)
            }
            if (status == DownloadStatus.UNKNOWN) {
                return toDomain(existing)
            }
            val reset = existing.copy(
                title = request.title,
                streamIdentity = request.contentId,
                state = DownloadStatus.QUEUED.name,
                bytesDownloaded = 0L,
                totalBytes = request.expectedBytes,
                localReference = null,
                integrityMetadata = null,
                failureReason = null,
                updatedAt = clock(),
            )
            dao.upsert(reset)
            return toDomain(reset)
        }

        val now = clock()
        val created = DownloadEntity(
            downloadId = DownloadIdentity.stableId(
                sourceId = request.sourceId,
                mediaKind = request.mediaKind,
                contentId = request.contentId,
            ).value,
            sourceId = request.sourceId.value,
            mediaKind = request.mediaKind.name,
            contentId = request.contentId,
            title = request.title,
            streamIdentity = request.contentId,
            state = DownloadStatus.QUEUED.name,
            bytesDownloaded = 0L,
            totalBytes = request.expectedBytes,
            localReference = null,
            integrityMetadata = null,
            failureReason = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(created)
        return toDomain(created)
    }

    override suspend fun markWaitingForWifi(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.WAITING_FOR_WIFI)

    override suspend fun markQueued(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.QUEUED)

    override suspend fun markDownloading(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.DOWNLOADING)

    override suspend fun updateProgress(
        downloadId: DownloadId,
        bytesDownloaded: Long,
        totalBytes: Long?,
    ): Boolean {
        if (!DownloadProgressPolicy.isValid(bytesDownloaded, totalBytes)) return false
        val existing = dao.get(downloadId.value) ?: return false
        val status = persistedStatus(existing.state)
        if (status != DownloadStatus.DOWNLOADING) return false
        dao.upsert(
            existing.copy(
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun pause(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.PAUSED)

    override suspend fun resume(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.QUEUED)

    override suspend fun cancel(downloadId: DownloadId): Boolean =
        transition(downloadId, DownloadStatus.CANCELED)

    override suspend fun complete(
        downloadId: DownloadId,
        localReference: String,
        verifiedBytes: Long,
        sha256: String?,
    ): Boolean {
        if (localReference.isBlank() || verifiedBytes <= 0L) return false
        val normalizedDigest = sha256?.let(DownloadIntegrityPolicy::normalizeSha256)
        if (sha256 != null && normalizedDigest == null) return false
        val existing = dao.get(downloadId.value) ?: return false
        val current = persistedStatus(existing.state)
        if (!DownloadStateTransitionPolicy.canTransition(current, DownloadStatus.COMPLETED)) return false
        if (existing.totalBytes != null && existing.totalBytes != verifiedBytes) return false
        dao.upsert(
            existing.copy(
                state = DownloadStatus.COMPLETED.name,
                bytesDownloaded = verifiedBytes,
                totalBytes = verifiedBytes,
                localReference = localReference,
                integrityMetadata = DownloadIntegrityMetadata.encode(verifiedBytes, normalizedDigest),
                failureReason = null,
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun fail(
        downloadId: DownloadId,
        failureCode: DownloadFailureCode,
    ): Boolean {
        val existing = dao.get(downloadId.value) ?: return false
        val current = persistedStatus(existing.state)
        val allowed = if (current == DownloadStatus.COMPLETED) {
            failureCode == DownloadFailureCode.INTEGRITY
        } else {
            DownloadStateTransitionPolicy.canTransition(current, DownloadStatus.FAILED)
        }
        if (!allowed) return false
        dao.upsert(
            existing.copy(
                state = if (
                    current == DownloadStatus.COMPLETED &&
                    failureCode == DownloadFailureCode.INTEGRITY
                ) {
                    DownloadStatus.MISSING.name
                } else {
                    DownloadStatus.FAILED.name
                },
                localReference = null,
                integrityMetadata = null,
                failureReason = failureCode.name,
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun remove(downloadId: DownloadId): Boolean = dao.delete(downloadId.value) > 0

    private suspend fun transition(
        downloadId: DownloadId,
        target: DownloadStatus,
    ): Boolean {
        val existing = dao.get(downloadId.value) ?: return false
        val current = persistedStatus(existing.state)
        if (!DownloadStateTransitionPolicy.canTransition(current, target)) return false
        dao.upsert(
            existing.copy(
                state = target.name,
                failureReason = if (target == DownloadStatus.QUEUED) null else existing.failureReason,
                updatedAt = clock(),
            ),
        )
        return true
    }

    private fun toDomain(entity: DownloadEntity): DownloadItem {
        val integrity = DownloadIntegrityMetadata.decode(entity.integrityMetadata)
        return DownloadItem(
            downloadId = DownloadId(entity.downloadId),
            sourceId = SourceId(entity.sourceId),
            mediaKind = runCatching { DownloadMediaKind.valueOf(entity.mediaKind) }
                .getOrElse { DownloadMediaKind.MOVIE },
            contentId = entity.contentId,
            title = entity.title,
            status = persistedStatus(entity.state),
            bytesDownloaded = entity.bytesDownloaded.coerceAtLeast(0L),
            totalBytes = entity.totalBytes?.takeIf { it > 0L && it >= entity.bytesDownloaded },
            localReference = entity.localReference,
            verifiedBytes = integrity?.verifiedBytes,
            sha256 = integrity?.sha256,
            failureCode = entity.failureReason?.let { persistedFailureCode(it) },
            createdAtEpochMs = entity.createdAt,
            updatedAtEpochMs = entity.updatedAt,
        )
    }

    private fun persistedStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.UNKNOWN)

    private fun persistedFailureCode(value: String): DownloadFailureCode =
        runCatching { DownloadFailureCode.valueOf(value) }.getOrDefault(DownloadFailureCode.UNKNOWN)
}

internal object DownloadIdentity {
    fun stableId(
        sourceId: SourceId,
        mediaKind: DownloadMediaKind,
        contentId: String,
    ): DownloadId {
        val canonical = listOf(sourceId.value, mediaKind.name, contentId).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return DownloadId("download:" + digest.joinToString("") { byte -> "%02x".format(byte) })
    }
}

private data class DownloadIntegrityMetadata(
    val verifiedBytes: Long,
    val sha256: String?,
) {
    companion object {
        fun encode(
            verifiedBytes: Long,
            sha256: String?,
        ): String = buildString {
            append("bytes=")
            append(verifiedBytes)
            sha256?.let {
                append(";sha256=")
                append(it)
            }
        }

        fun decode(value: String?): DownloadIntegrityMetadata? {
            val candidate = value?.takeIf(String::isNotBlank) ?: return null
            val parts = candidate.split(';')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
                }
                .toMap()
            val verifiedBytes = parts["bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val digest = parts["sha256"]?.let(DownloadIntegrityPolicy::normalizeSha256)
            if (parts.containsKey("sha256") && digest == null) return null
            return DownloadIntegrityMetadata(verifiedBytes = verifiedBytes, sha256 = digest)
        }
    }
}
