package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadFinalizationPhase
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutorFinalizationTest {
    @Test
    fun publishedOutputIsRecoveredAfterRoomCompletionFailure() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage()
        val firstRepository = FailOnceCompleteRepository(store.repository)
        val firstExecutor = executor(firstRepository, storage, PAYLOAD)

        assertEquals(DownloadExecutionOutcome.PERSISTED_FAILURE, firstExecutor.execute(queued.downloadId))
        assertTrue(storage.publishedBytes().contentEquals(PAYLOAD))
        assertNotNull(store.repository.get(queued.downloadId)?.finalization)
        assertEquals(DownloadStatus.DOWNLOADING, store.repository.get(queued.downloadId)?.status)

        val restartExecutor = executor(store.repository, storage, PAYLOAD)
        assertEquals(DownloadExecutionOutcome.COMPLETED, restartExecutor.execute(queued.downloadId))
        val completed = requireNotNull(store.repository.get(queued.downloadId))
        assertEquals(DownloadStatus.COMPLETED, completed.status)
        assertEquals(PAYLOAD.size.toLong(), completed.verifiedBytes)
        assertEquals(sha256(PAYLOAD), completed.sha256)
        assertNull(completed.finalization)
        assertEquals(1, storage.publishCount)
    }

    @Test
    fun completedIntegrityAndCleanupDescriptorSurviveUntilBackupCleanup() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage().apply { failFinalizeOnce = true }

        assertEquals(DownloadExecutionOutcome.COMPLETED, executor(store.repository, storage, PAYLOAD).execute(queued.downloadId))
        val committed = requireNotNull(store.repository.get(queued.downloadId))
        assertEquals(DownloadStatus.COMPLETED, committed.status)
        assertNotNull(committed.finalization)
        assertEquals(PAYLOAD.size.toLong(), committed.verifiedBytes)

        executor(store.repository, storage, PAYLOAD).recoverInterruptedFinalizations(listOf(committed))

        val cleaned = requireNotNull(store.repository.get(queued.downloadId))
        assertNull(cleaned.finalization)
        assertEquals(PAYLOAD.size.toLong(), cleaned.verifiedBytes)
        assertEquals(sha256(PAYLOAD), cleaned.sha256)
    }

    @Test
    fun verifiedStagingIsPublishedOnlyAfterByteAndHashRevalidation() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage()
        storage.installStaging(queued.downloadId, PAYLOAD)
        assertTrue(store.repository.recordOutputIntent(queued.downloadId, storage.descriptor(queued.downloadId)))
        assertTrue(store.repository.markOutputVerified(queued.downloadId, PAYLOAD.size.toLong(), sha256(PAYLOAD)))

        val executor = executor(store.repository, storage, byteArrayOf(9, 8, 7))
        executor.recoverInterruptedFinalizations(listOf(requireNotNull(store.repository.get(queued.downloadId))))

        assertEquals(DownloadStatus.COMPLETED, store.repository.get(queued.downloadId)?.status)
        assertTrue(storage.publishedBytes().contentEquals(PAYLOAD))
        assertEquals(1, storage.publishCount)
    }

    @Test
    fun mismatchedVerifiedStagingIsDiscardedWithoutCompletingTheRow() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage()
        storage.installStaging(queued.downloadId, byteArrayOf(1, 2, 3))
        assertTrue(store.repository.recordOutputIntent(queued.downloadId, storage.descriptor(queued.downloadId)))
        assertTrue(store.repository.markOutputVerified(queued.downloadId, PAYLOAD.size.toLong(), sha256(PAYLOAD)))

        executor(store.repository, storage, PAYLOAD).recoverInterruptedFinalizations(
            listOf(requireNotNull(store.repository.get(queued.downloadId))),
        )

        assertEquals(DownloadStatus.DOWNLOADING, store.repository.get(queued.downloadId)?.status)
        assertNull(store.repository.get(queued.downloadId)?.finalization)
        assertEquals(0, storage.publishCount)
        assertTrue(storage.stagingBytes().isEmpty())
    }

    @Test
    fun retryDoesNotEraseAnUnreconciledFinalizationDescriptor() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage()
        assertTrue(store.repository.recordOutputIntent(queued.downloadId, storage.descriptor(queued.downloadId)))
        assertTrue(store.repository.fail(queued.downloadId, DownloadFailureCode.NETWORK))

        val retry = store.repository.enqueue(store.request)

        assertEquals(DownloadStatus.FAILED, retry.status)
        assertNotNull(retry.finalization)
        assertNotNull(store.rows[queued.downloadId.value]?.integrityMetadata)
    }

    @Test
    fun verifiedFinalizationClearsAfterPendingOutputWasAlreadyRemoved() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        val storage = MemoryStorage()
        storage.installStaging(queued.downloadId, PAYLOAD)
        assertTrue(store.repository.recordOutputIntent(queued.downloadId, storage.descriptor(queued.downloadId)))
        assertTrue(store.repository.markOutputVerified(queued.downloadId, PAYLOAD.size.toLong(), sha256(PAYLOAD)))
        storage.simulatePendingRemovedBeforeMetadataCleanup()

        executor(store.repository, storage, PAYLOAD).recoverInterruptedFinalizations(
            listOf(requireNotNull(store.repository.get(queued.downloadId))),
        )

        assertNull(store.repository.get(queued.downloadId)?.finalization)
        assertEquals(DownloadStatus.DOWNLOADING, store.repository.get(queued.downloadId)?.status)
        assertEquals(0, storage.publishCount)
    }

    @Test
    fun cancellationWinsAgainstLateCompletionWithVerifiedDescriptor() = runBlocking {
        val store = DownloadTestStore()
        val queued = store.repository.enqueue(store.request)
        assertTrue(store.repository.markDownloading(queued.downloadId))
        assertTrue(store.repository.recordOutputIntent(queued.downloadId, "owned-descriptor"))
        assertTrue(store.repository.markOutputVerified(queued.downloadId, PAYLOAD.size.toLong(), sha256(PAYLOAD)))
        assertTrue(store.repository.cancel(queued.downloadId))

        assertTrue(!store.repository.complete(queued.downloadId, "file:///owned/output", PAYLOAD.size.toLong(), sha256(PAYLOAD)))

        val canceled = requireNotNull(store.repository.get(queued.downloadId))
        assertEquals(DownloadStatus.CANCELED, canceled.status)
        assertEquals(DownloadFinalizationPhase.VERIFIED, canceled.finalization?.phase)
    }

    private fun executor(
        repository: DownloadRepository,
        storage: MemoryStorage,
        bytes: ByteArray,
    ) = DownloadExecutor(
        repository = repository,
        mediaResolver = object : DownloadMediaResolver {
            override suspend fun resolve(item: app.ownplay.mobile.downloads.domain.DownloadItem) =
                ResolvedDownloadMedia("https://source.invalid/video.mp4", "mp4", "Video.mp4", listOf("Movies"))
        },
        storage = storage,
        transferClient = PayloadTransferClient(bytes),
    )

    private class FailOnceCompleteRepository(
        private val delegate: DownloadRepository,
    ) : DownloadRepository by delegate {
        private var fail = true

        override suspend fun complete(
            downloadId: DownloadId,
            localReference: String,
            verifiedBytes: Long,
            sha256: String?,
        ): Boolean {
            if (fail) {
                fail = false
                return false
            }
            return delegate.complete(downloadId, localReference, verifiedBytes, sha256)
        }
    }

    private class PayloadTransferClient(private val bytes: ByteArray) : DownloadTransferClient {
        override suspend fun transfer(
            uri: String,
            output: OutputStream,
            onProgress: suspend (bytesTransferred: Long, totalBytes: Long?) -> Unit,
        ): DownloadTransferResult {
            output.write(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return DownloadTransferResult(bytes.size.toLong(), bytes.size.toLong(), sha256(bytes))
        }
    }

    private class MemoryStorage : DownloadStorage {
        private var currentId: DownloadId? = null
        private var staging = ByteArrayOutputStream()
        private var published: ByteArray? = null
        private var pendingMissing = false
        var failFinalizeOnce = false
        var publishCount = 0
            private set

        fun descriptor(id: DownloadId) = "ownplay-output:${id.value}"

        fun installStaging(id: DownloadId, bytes: ByteArray) {
            currentId = id
            pendingMissing = false
            staging = ByteArrayOutputStream().apply { write(bytes) }
        }

        fun simulatePendingRemovedBeforeMetadataCleanup() {
            staging.reset()
            pendingMissing = true
        }

        fun stagingBytes(): ByteArray = staging.toByteArray()
        fun publishedBytes(): ByteArray = requireNotNull(published)

        override suspend fun openPending(downloadId: DownloadId, media: ResolvedDownloadMedia): PendingDownloadOutput? {
            currentId = downloadId
            pendingMissing = false
            staging = ByteArrayOutputStream()
            val file = File("/tmp/${downloadId.value.filter(Char::isLetterOrDigit)}.part")
            return PendingDownloadOutput(staging, PendingDownloadToken.PrivateFile(file, File(file.parentFile, "final.mp4")))
        }

        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long = staging.size().toLong()

        override suspend fun publish(pending: PendingDownloadOutput): String {
            published = staging.toByteArray()
            publishCount += 1
            (pending.token as PendingDownloadToken.PrivateFile).published = true
            return reference(currentId ?: error("No active id"))
        }

        override suspend fun discard(pending: PendingDownloadOutput) {
            staging.reset()
            pendingMissing = true
            if ((pending.token as? PendingDownloadToken.PrivateFile)?.published == true) published = null
        }

        override suspend fun discardPending(downloadId: DownloadId): Boolean = true
        override suspend fun removePublished(localReference: String): Boolean { published = null; return true }

        override fun describePending(pending: PendingDownloadOutput): String = descriptor(currentId ?: error("No active id"))

        override fun recoverOwnedPending(downloadId: DownloadId, descriptor: String): PendingDownloadOutput? {
            if (descriptor != descriptor(downloadId) || currentId != downloadId) return null
            val file = File("/tmp/${downloadId.value.filter(Char::isLetterOrDigit)}.part")
            return PendingDownloadOutput(
                staging,
                PendingDownloadToken.PrivateFile(
                    file,
                    File(file.parentFile, "final.mp4"),
                    pendingMissing = pendingMissing,
                ),
            )
        }

        override fun pendingOutputMissing(pending: PendingDownloadOutput): Boolean =
            (pending.token as? PendingDownloadToken.PrivateFile)?.pendingMissing == true

        override fun openPendingInput(pending: PendingDownloadOutput) = ByteArrayInputStream(staging.toByteArray())

        override suspend fun finalizePublished(pending: PendingDownloadOutput, localReference: String): Boolean {
            if (failFinalizeOnce) {
                failFinalizeOnce = false
                return false
            }
            return true
        }

        override fun recoverOwnedPublished(downloadId: DownloadId, descriptor: String): RecoveredPublishedDownloadOutput? {
            if (descriptor != descriptor(downloadId)) return null
            val bytes = published ?: return null
            return RecoveredPublishedDownloadOutput(reference(downloadId), ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        override suspend fun finalizeRecoveredPublished(
            downloadId: DownloadId,
            descriptor: String,
            localReference: String,
        ): Boolean = descriptor == descriptor(downloadId) && localReference == reference(downloadId)

        override suspend fun rollbackRecoveredPublished(
            downloadId: DownloadId,
            descriptor: String,
            expectedBytes: Long,
            expectedSha256: String,
        ): Boolean {
            if (descriptor != descriptor(downloadId)) return false
            val bytes = published ?: return true
            if (bytes.size.toLong() != expectedBytes || sha256(bytes) != expectedSha256) return false
            published = null
            return true
        }

        private fun reference(id: DownloadId) = "file:///ownplay/${id.value}"
    }

    private companion object {
        val PAYLOAD = "persisted-download-payload".toByteArray()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
