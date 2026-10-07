package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.DownloadStorage
import app.ownplay.mobile.downloads.data.PendingDownloadOutput
import app.ownplay.mobile.downloads.data.PendingDownloadToken
import app.ownplay.mobile.downloads.data.RecoveredPublishedDownloadOutput
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRecordingRecoveryCoordinatorTest {
    @Test
    fun verifiedPendingIsPublishedOnlyAfterDurableIntentAndThenCommitted() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.COMPLETED_FINALIZING)
        val repository = FakeRepository(initial)
        val storage = FakeStorage(pendingBytes = transportStream()) {
            val current = requireNotNull(repository.get(initial.recordingId))
            assertEquals(LiveRecordingStatus.FINALIZING, current.status)
            assertEquals(LiveRecordingFinalizationState.COMPLETED_FINALIZING, current.finalizationState)
            assertEquals(DESCRIPTOR, current.pendingOutputDescriptor)
        }

        LiveRecordingRecoveryCoordinator(repository, storage).recover(listOf(initial))

        val completed = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.COMPLETED, completed.status)
        assertEquals(LiveRecordingFinalizationState.PUBLISHED, completed.finalizationState)
        assertEquals(REFERENCE, completed.localReference)
        assertNull(completed.pendingOutputDescriptor)
        assertEquals(1, storage.publishCalls)
        assertEquals(1, storage.finalizeCalls)
    }

    @Test
    fun publishedOutputSurvivesFailedMetadataCommitAndIsRecoveredOnce() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.COMPLETED_FINALIZING)
        val repository = FakeRepository(initial, failedCompletedPuts = 1)
        val storage = FakeStorage(publishedBytes = transportStream())
        val coordinator = LiveRecordingRecoveryCoordinator(repository, storage)

        coordinator.recover(listOf(initial))

        val afterFailedCommit = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.FINALIZING, afterFailedCommit.status)
        assertEquals(DESCRIPTOR, afterFailedCommit.pendingOutputDescriptor)
        assertEquals(0, storage.finalizeCalls)
        assertEquals(0, storage.removeCalls)

        coordinator.recover(listOf(afterFailedCommit))

        val completed = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.COMPLETED, completed.status)
        assertEquals(REFERENCE, completed.localReference)
        assertNull(completed.pendingOutputDescriptor)
        assertEquals(1, storage.finalizeCalls)
        assertEquals(0, storage.removeCalls)
    }

    @Test
    fun invalidOwnedTransportStreamIsRemovedAndNeverAttached() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.COMPLETED_FINALIZING)
        val repository = FakeRepository(initial)
        val storage = FakeStorage(publishedBytes = ByteArray(188 * 64) { 0 })

        LiveRecordingRecoveryCoordinator(repository, storage).recover(listOf(initial))

        val failed = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.FAILED, failed.status)
        assertNull(failed.localReference)
        assertNull(failed.pendingOutputDescriptor)
        assertEquals(1, storage.removeCalls)
        assertEquals(0, storage.finalizeCalls)
    }

    @Test
    fun unownedOutputIsNeitherAdoptedNorUsedToClearRecoveryDescriptor() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.COMPLETED_FINALIZING)
        val repository = FakeRepository(initial)
        val storage = FakeStorage(exposeOwnedOutput = false)

        LiveRecordingRecoveryCoordinator(repository, storage).recover(listOf(initial))

        val retained = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.FINALIZING, retained.status)
        assertEquals(DESCRIPTOR, retained.pendingOutputDescriptor)
        assertNull(retained.localReference)
        assertEquals(0, storage.publishCalls)
        assertEquals(0, storage.removeCalls)
    }

    @Test
    fun temporarilyUnreadableOwnedOutputRetainsDescriptorForRetry() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.COMPLETED_FINALIZING)
        val repository = FakeRepository(initial)
        val storage = FakeStorage(publishedBytes = transportStream(), publishedReadFailure = true)

        LiveRecordingRecoveryCoordinator(repository, storage).recover(listOf(initial))

        val retained = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.FINALIZING, retained.status)
        assertEquals(DESCRIPTOR, retained.pendingOutputDescriptor)
        assertEquals(0, storage.removeCalls)
    }

    @Test
    fun partialFinalizationRecoversAsPartialRatherThanFullRecording() = runBlocking {
        val initial = finalizing(LiveRecordingFinalizationState.PARTIAL_FINALIZING)
        val repository = FakeRepository(initial)
        val storage = FakeStorage(pendingBytes = transportStream())

        LiveRecordingRecoveryCoordinator(repository, storage).recover(listOf(initial))

        val partial = requireNotNull(repository.get(initial.recordingId))
        assertEquals(LiveRecordingStatus.PARTIAL, partial.status)
        assertEquals(LiveRecordingFinalizationState.PARTIAL_PUBLISHED, partial.finalizationState)
        assertEquals(REFERENCE, partial.partialLocalReference)
        assertNull(partial.pendingOutputDescriptor)
    }

    private fun finalizing(state: LiveRecordingFinalizationState) = LiveRecording(
        recordingId = "recording-1",
        sourceId = "source-1",
        channelId = "channel-1",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = 1_000L,
        endEpochSeconds = 2_000L,
        status = LiveRecordingStatus.FINALIZING,
        pendingOutputDescriptor = DESCRIPTOR,
        finalizationState = state,
    )

    private class FakeRepository(
        initial: LiveRecording,
        private var failedCompletedPuts: Int = 0,
    ) : LiveRecordingRepository {
        private val state = MutableStateFlow(listOf(initial))
        override val recordings: StateFlow<List<LiveRecording>> = state
        override fun get(recordingId: String): LiveRecording? = state.value.firstOrNull { it.recordingId == recordingId }

        override fun put(recording: LiveRecording): Boolean {
            if (recording.status in setOf(LiveRecordingStatus.COMPLETED, LiveRecordingStatus.PARTIAL) &&
                failedCompletedPuts > 0
            ) {
                failedCompletedPuts -= 1
                return false
            }
            state.value = state.value.filterNot { it.recordingId == recording.recordingId } + recording
            return true
        }

        override fun update(recordingId: String, transform: (LiveRecording) -> LiveRecording): Boolean {
            val current = get(recordingId) ?: return false
            return put(transform(current))
        }

        override fun remove(recordingId: String): Boolean {
            state.value = state.value.filterNot { it.recordingId == recordingId }
            return true
        }
    }

    private class FakeStorage(
        private val pendingBytes: ByteArray? = null,
        private var publishedBytes: ByteArray? = null,
        private val exposeOwnedOutput: Boolean = true,
        private val publishedReadFailure: Boolean = false,
        private val onPublish: () -> Unit = {},
    ) : DownloadStorage {
        private var pendingAvailable = pendingBytes != null
        var publishCalls = 0
        var finalizeCalls = 0
        var removeCalls = 0

        override suspend fun openPending(downloadId: DownloadId, media: ResolvedDownloadMedia): PendingDownloadOutput? = null
        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = pendingBytes?.size?.toLong()
        override suspend fun publish(pending: PendingDownloadOutput): String {
            onPublish()
            publishCalls += 1
            publishedBytes = pendingBytes
            pendingAvailable = false
            return REFERENCE
        }
        override suspend fun discard(pending: PendingDownloadOutput) { pendingAvailable = false }
        override suspend fun discardPending(downloadId: DownloadId): Boolean = true
        override suspend fun removePublished(localReference: String): Boolean {
            removeCalls += 1
            publishedBytes = null
            return localReference == REFERENCE
        }
        override fun describePending(pending: PendingDownloadOutput): String = DESCRIPTOR
        override fun recoverOwnedPending(downloadId: DownloadId, descriptor: String): PendingDownloadOutput? {
            if (!exposeOwnedOutput || descriptor != DESCRIPTOR || !pendingAvailable || pendingBytes == null) return null
            return PendingDownloadOutput(ByteArrayOutputStream(), token())
        }
        override fun openPendingInput(pending: PendingDownloadOutput) = pendingBytes?.let { ByteArrayInputStream(it) }
        override fun recoverOwnedPublished(
            downloadId: DownloadId,
            descriptor: String,
        ): RecoveredPublishedDownloadOutput? {
            val bytes = publishedBytes ?: return null
            if (!exposeOwnedOutput || descriptor != DESCRIPTOR) return null
            val input = if (publishedReadFailure) {
                object : InputStream() {
                    override fun read(): Int = throw java.io.IOException("Transient read failure")
                }
            } else {
                ByteArrayInputStream(bytes)
            }
            return RecoveredPublishedDownloadOutput(REFERENCE, input, bytes.size.toLong())
        }
        override suspend fun finalizePublished(pending: PendingDownloadOutput, localReference: String): Boolean {
            finalizeCalls += 1
            return localReference == REFERENCE
        }
        override suspend fun finalizeRecoveredPublished(
            downloadId: DownloadId,
            descriptor: String,
            localReference: String,
        ): Boolean {
            finalizeCalls += 1
            return exposeOwnedOutput && descriptor == DESCRIPTOR && localReference == REFERENCE
        }

        private fun token() = PendingDownloadToken.PrivateFile(
            File("/app/files/pending/recording-1.part"),
            File("/app/files/completed/recording.ts"),
            replaceExisting = false,
        )
    }

    private companion object {
        const val DESCRIPTOR = "{\"kind\":\"private-file\",\"reference\":\"/app/files/pending/recording-1.part\"}"
        const val REFERENCE = "file:///app/files/completed/recording.ts"

        fun transportStream(): ByteArray = ByteArray(188 * 64) { index ->
            if (index % 188 == 0) 0x47 else 0
        }
    }
}
