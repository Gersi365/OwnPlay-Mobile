package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadFinalization
import app.ownplay.mobile.downloads.domain.DownloadFinalizationPhase
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadIntegrityPolicy
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadOrigin
import app.ownplay.mobile.downloads.domain.DownloadProgressPolicy
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStateTransitionPolicy
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class RoomDownloadRepository(
    private val dao: DownloadDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadRepository {
    override fun observeDownloads(sourceId: SourceId): Flow<List<DownloadItem>> =
        dao.observeForSource(sourceId.value).map { rows -> rows.map(::toDomain) }

    override fun observeAllDownloads(): Flow<List<DownloadItem>> =
        dao.observeAll().map { rows -> rows.map(::toDomain) }

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
            if (DownloadFinalizationCodec.hasFinalization(existing.integrityMetadata)) {
                // Recovery owns this row until its physical staging/published file is reconciled.
                return toDomain(existing)
            }
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
                streamIdentity = request.sourceContentIdentity ?: request.contentId,
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
            streamIdentity = request.sourceContentIdentity ?: request.contentId,
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
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(existing.integrityMetadata) ?: return false
        val previousIntegrity = DownloadIntegrityMetadata.decode(storedMetadata.integrityMetadata)
        val verifiedFinalization = current in setOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.QUEUED,
            DownloadStatus.WAITING_FOR_WIFI,
        ) && storedMetadata.finalization?.let {
            it.phase == DownloadFinalizationPhase.VERIFIED &&
                it.verifiedBytes == verifiedBytes && it.sha256 == normalizedDigest
        } == true
        val restoringUnavailable = current == DownloadStatus.MISSING &&
            existing.localReference == localReference &&
            previousIntegrity?.verifiedBytes == verifiedBytes &&
            previousIntegrity.sha256 == normalizedDigest
        if (!restoringUnavailable && !verifiedFinalization &&
            !DownloadStateTransitionPolicy.canTransition(current, DownloadStatus.COMPLETED)
        ) return false
        if (existing.totalBytes != null && existing.totalBytes != verifiedBytes) return false
        dao.upsert(
            existing.copy(
                state = DownloadStatus.COMPLETED.name,
                bytesDownloaded = verifiedBytes,
                totalBytes = verifiedBytes,
                localReference = localReference,
                integrityMetadata = if (restoringUnavailable) {
                    existing.integrityMetadata
                } else {
                    DownloadFinalizationCodec.encodePersisted(
                        integrityMetadata = DownloadIntegrityMetadata.encode(
                            verifiedBytes = verifiedBytes,
                            sha256 = normalizedDigest,
                            origin = DownloadOrigin.APP_MANAGED,
                        ),
                        finalization = storedMetadata.finalization,
                    )
                },
                failureReason = null,
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun recordOutputIntent(downloadId: DownloadId, descriptor: String): Boolean {
        if (descriptor.isBlank()) return false
        val existing = dao.get(downloadId.value) ?: return false
        if (persistedStatus(existing.state) != DownloadStatus.DOWNLOADING) return false
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(existing.integrityMetadata) ?: return false
        dao.upsert(
            existing.copy(
                integrityMetadata = DownloadFinalizationCodec.encodePersisted(
                    integrityMetadata = storedMetadata.integrityMetadata,
                    finalization = DownloadFinalization(descriptor, DownloadFinalizationPhase.STAGING),
                ),
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun markOutputVerified(
        downloadId: DownloadId,
        verifiedBytes: Long,
        sha256: String,
    ): Boolean {
        if (verifiedBytes <= 0L) return false
        val digest = DownloadIntegrityPolicy.normalizeSha256(sha256) ?: return false
        val existing = dao.get(downloadId.value) ?: return false
        if (persistedStatus(existing.state) != DownloadStatus.DOWNLOADING) return false
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(existing.integrityMetadata) ?: return false
        val intent = storedMetadata.finalization ?: return false
        dao.upsert(
            existing.copy(
                integrityMetadata = DownloadFinalizationCodec.encodePersisted(
                    integrityMetadata = storedMetadata.integrityMetadata,
                    finalization = intent.copy(
                        phase = DownloadFinalizationPhase.VERIFIED,
                        verifiedBytes = verifiedBytes,
                        sha256 = digest,
                    ),
                ),
                updatedAt = clock(),
            ),
        )
        return true
    }

    override suspend fun clearOutputFinalization(downloadId: DownloadId): Boolean {
        val existing = dao.get(downloadId.value) ?: return false
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(existing.integrityMetadata) ?: return false
        if (storedMetadata.finalization == null) return true
        dao.upsert(
            existing.copy(
                integrityMetadata = DownloadFinalizationCodec.encodePersisted(
                    integrityMetadata = storedMetadata.integrityMetadata,
                    finalization = null,
                ),
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
        val completedOutputFailure = current == DownloadStatus.COMPLETED &&
            failureCode in setOf(
                DownloadFailureCode.INTEGRITY,
                DownloadFailureCode.LOCAL_MISSING,
                DownloadFailureCode.LOCAL_UNAVAILABLE,
            )
        val allowed = if (current == DownloadStatus.COMPLETED) {
            completedOutputFailure
        } else {
            DownloadStateTransitionPolicy.canTransition(current, DownloadStatus.FAILED)
        }
        if (!allowed) return false
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(existing.integrityMetadata) ?: return false
        dao.upsert(
            existing.copy(
                state = if (completedOutputFailure) {
                    DownloadStatus.MISSING.name
                } else {
                    DownloadStatus.FAILED.name
                },
                localReference = if (completedOutputFailure) existing.localReference else null,
                integrityMetadata = DownloadFinalizationCodec.encodePersisted(
                    integrityMetadata = if (completedOutputFailure) {
                        storedMetadata.integrityMetadata
                    } else {
                        null
                    },
                    finalization = storedMetadata.finalization,
                ),
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
        val storedMetadata = DownloadFinalizationCodec.decodePersisted(entity.integrityMetadata)
        val integrity = DownloadIntegrityMetadata.decode(storedMetadata?.integrityMetadata)
        val finalization = storedMetadata?.finalization ?: if (
            storedMetadata == null && DownloadFinalizationCodec.hasFinalization(entity.integrityMetadata)
        ) {
            DownloadFinalization(entity.integrityMetadata.orEmpty(), DownloadFinalizationPhase.STAGING)
        } else {
            null
        }
        return DownloadItem(
            downloadId = DownloadId(entity.downloadId),
            sourceId = SourceId(entity.sourceId),
            mediaKind = runCatching { DownloadMediaKind.valueOf(entity.mediaKind) }
                .getOrElse { DownloadMediaKind.MOVIE },
            contentId = entity.contentId,
            title = entity.title,
            status = if (storedMetadata == null && DownloadFinalizationCodec.hasFinalization(entity.integrityMetadata)) {
                DownloadStatus.UNKNOWN
            } else {
                persistedStatus(entity.state)
            },
            bytesDownloaded = entity.bytesDownloaded.coerceAtLeast(0L),
            totalBytes = entity.totalBytes?.takeIf { it > 0L && it >= entity.bytesDownloaded },
            localReference = entity.localReference,
            verifiedBytes = integrity?.verifiedBytes,
            sha256 = integrity?.sha256,
            failureCode = entity.failureReason?.let { persistedFailureCode(it) },
            createdAtEpochMs = entity.createdAt,
            updatedAtEpochMs = entity.updatedAt,
            origin = integrity?.origin ?: DownloadOrigin.APP_MANAGED,
            sourceContentIdentity = entity.streamIdentity,
            finalization = finalization,
        )
    }

    private fun persistedStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.UNKNOWN)

    private fun persistedFailureCode(value: String): DownloadFailureCode =
        runCatching { DownloadFailureCode.valueOf(value) }.getOrDefault(DownloadFailureCode.UNKNOWN)
}

internal object DownloadFinalizationCodec {
    private const val PREFIX = "ownplay-metadata-v2:"

    fun encode(value: DownloadFinalization): String = JSONObject()
        .put("descriptor", value.descriptor)
        .put("phase", value.phase.name)
        .put("verifiedBytes", value.verifiedBytes)
        .put("sha256", value.sha256)
        .toString()

    fun decodePersisted(raw: String?): PersistedDownloadMetadata? {
        if (raw.isNullOrBlank()) return PersistedDownloadMetadata(null, null)
        if (!raw.startsWith(PREFIX)) return PersistedDownloadMetadata(raw, null)
        return runCatching {
            val json = JSONObject(raw.removePrefix(PREFIX))
            require(json.getInt("version") == 2)
            val integrity = if (json.isNull("integrity")) null else json.getString("integrity")
            val finalization = decodeValue(json.getString("finalization")) ?: error("Invalid finalization")
            PersistedDownloadMetadata(integrity, finalization)
        }.getOrNull()
    }

    fun hasFinalization(raw: String?): Boolean =
        raw?.startsWith(PREFIX) == true || decodeValue(raw) != null

    fun encodePersisted(
        integrityMetadata: String?,
        finalization: DownloadFinalization?,
    ): String? {
        if (finalization == null) return integrityMetadata
        return PREFIX + JSONObject()
            .put("version", 2)
            .put("integrity", integrityMetadata)
            .put("finalization", encode(finalization))
            .toString()
    }

    fun decode(raw: String?): DownloadFinalization? {
        if (raw.isNullOrBlank()) return null
        decodePersisted(raw)?.let { persisted ->
            if (raw.startsWith(PREFIX)) return persisted.finalization
        }
        return decodeValue(raw)
    }

    private fun decodeValue(raw: String?): DownloadFinalization? {
        if (raw.isNullOrBlank()) return null
        val decoded = runCatching {
            val json = JSONObject(raw)
            val phase = DownloadFinalizationPhase.valueOf(json.getString("phase"))
            val verifiedBytes = json.optLong("verifiedBytes").takeIf { it > 0L }
            val sha256 = json.optString("sha256").takeIf(String::isNotBlank)
            DownloadFinalization(json.getString("descriptor"), phase, verifiedBytes, sha256)
        }.getOrNull()
        if (decoded?.phase == DownloadFinalizationPhase.VERIFIED &&
            (decoded.verifiedBytes == null || decoded.sha256 == null)
        ) return null
        return decoded
    }
}

internal data class PersistedDownloadMetadata(
    val integrityMetadata: String?,
    val finalization: DownloadFinalization?,
)

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

internal data class DownloadIntegrityMetadata(
    val verifiedBytes: Long,
    val sha256: String?,
    val origin: DownloadOrigin,
) {
    companion object {
        fun encode(
            verifiedBytes: Long,
            sha256: String?,
            origin: DownloadOrigin = DownloadOrigin.APP_MANAGED,
        ): String = buildString {
            append("bytes=")
            append(verifiedBytes)
            sha256?.let {
                append(";sha256=")
                append(it)
            }
            append(";origin=")
            append(origin.name)
        }

        fun decode(value: String?): DownloadIntegrityMetadata? {
            val candidate = DownloadFinalizationCodec.decodePersisted(value)
                ?.integrityMetadata
                ?.takeIf(String::isNotBlank)
                ?: return null
            val parts = candidate.split(';')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
                }
                .toMap()
            val verifiedBytes = parts["bytes"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val digest = parts["sha256"]?.let(DownloadIntegrityPolicy::normalizeSha256)
            if (parts.containsKey("sha256") && digest == null) return null
            val origin = parts["origin"]
                ?.let { value -> runCatching { DownloadOrigin.valueOf(value) }.getOrNull() }
                ?: DownloadOrigin.APP_MANAGED
            return DownloadIntegrityMetadata(
                verifiedBytes = verifiedBytes,
                sha256 = digest,
                origin = origin,
            )
        }
    }
}
