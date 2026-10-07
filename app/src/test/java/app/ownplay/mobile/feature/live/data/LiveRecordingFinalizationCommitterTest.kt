package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.DownloadStorage
import app.ownplay.mobile.downloads.data.PendingDownloadOutput
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecordingFinalizationCommitterTest {
    @Test
    fun partialCommitRetainsTheInitiatingPlaybackPreemptionCause() = runBlocking {
        val recording = finalizingRecording().copy(
            failureReason = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
            failureReasonCode = LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING,
            failureStage = "PLAYBACK_PREEMPTION",
            failureAtEpochMs = 1_234L,
            safeError = "Playback took priority.",
        )
        val repository = FakeRecordingRepository(recording)
        val committer = LiveRecordingFinalizationCommitter(repository, FakeStorage())

        assertTrue(
            committer.commitPublished(
                recording = recording,
                descriptor = DESCRIPTOR,
                reference = REFERENCE,
                size = 188L * 64L,
                partial = true,
                processInterrupted = false,
                partialWasUserStopped = false,
            ),
        )

        val partial = requireNotNull(repository.get(recording.recordingId))
        assertEquals(LiveRecordingStatus.PARTIAL, partial.status)
        assertEquals(LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING, partial.failureReasonCode)
        assertEquals(LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING, partial.failureReason)
        assertEquals("PLAYBACK_PREEMPTION", partial.failureStage)
        assertEquals(1_234L, partial.failureAtEpochMs)
        assertEquals("Playback started; a verified partial recording was saved.", partial.safeError)
    }

    @Test
    fun metadataCommitFailureRetainsFinalizationIntentUntilRecoveryCanCommit() = runBlocking {
        val recording = finalizingRecording()
        val repository = FakeRecordingRepository(recording, failedPuts = 1)
        val storage = FakeStorage()
        val committer = LiveRecordingFinalizationCommitter(repository, storage)

        assertFalse(
            committer.commitPublished(
                recording, DESCRIPTOR, REFERENCE, 188L * 20L,
                partial = false, processInterrupted = false,
            ),
        )
        assertEquals(LiveRecordingStatus.FINALIZING, repository.get(recording.recordingId)?.status)
        assertEquals(DESCRIPTOR, repository.get(recording.recordingId)?.pendingOutputDescriptor)
        assertEquals(0, storage.finalizeCalls)

        assertTrue(
            committer.commitPublished(
                requireNotNull(repository.get(recording.recordingId)),
                DESCRIPTOR,
                REFERENCE,
                188L * 20L,
                partial = false,
                processInterrupted = true,
            ),
        )
        val completed = requireNotNull(repository.get(recording.recordingId))
        assertEquals(LiveRecordingStatus.COMPLETED, completed.status)
        assertEquals(LiveRecordingFinalizationState.PUBLISHED, completed.finalizationState)
        assertNull(completed.pendingOutputDescriptor)
        assertEquals(1, storage.finalizeCalls)
    }

    @Test
    fun cleanupFailureKeepsPublishedDescriptorForAnIdempotentRestart() = runBlocking {
        val recording = finalizingRecording()
        val repository = FakeRecordingRepository(recording)
        val storage = FakeStorage(finalizeResult = false)
        val committer = LiveRecordingFinalizationCommitter(repository, storage)

        assertTrue(committer.commitPublished(recording, DESCRIPTOR, REFERENCE, 188L * 20L, false, false))
        assertEquals(LiveRecordingFinalizationState.PUBLISHED, repository.get(recording.recordingId)?.finalizationState)
        assertEquals(DESCRIPTOR, repository.get(recording.recordingId)?.pendingOutputDescriptor)

        storage.finalizeResult = true
        assertTrue(committer.commitPublished(
            requireNotNull(repository.get(recording.recordingId)), DESCRIPTOR, REFERENCE,
            188L * 20L, false, true,
        ))
        assertNull(repository.get(recording.recordingId)?.pendingOutputDescriptor)
        assertEquals(2, storage.finalizeCalls)
    }

    private fun finalizingRecording() = LiveRecording(
        recordingId = "recording-1",
        sourceId = "source-1",
        channelId = "channel-1",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = 1_000L,
        endEpochSeconds = 2_000L,
        status = LiveRecordingStatus.FINALIZING,
        pendingOutputDescriptor = DESCRIPTOR,
        finalizationState = LiveRecordingFinalizationState.FINALIZING,
    )

    private class FakeRecordingRepository(
        initial: LiveRecording,
        private var failedPuts: Int = 0,
    ) : LiveRecordingRepository {
        private val state = MutableStateFlow(listOf(initial))
        override val recordings: StateFlow<List<LiveRecording>> = state
        override fun get(recordingId: String) = state.value.firstOrNull { it.recordingId == recordingId }

        override fun put(recording: LiveRecording): Boolean {
            if (failedPuts > 0) {
                failedPuts -= 1
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
        var finalizeResult: Boolean = true,
    ) : DownloadStorage {
        var finalizeCalls = 0
        override suspend fun openPending(downloadId: DownloadId, media: ResolvedDownloadMedia): PendingDownloadOutput? = null
        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = null
        override suspend fun publish(pending: PendingDownloadOutput): String? = null
        override suspend fun discard(pending: PendingDownloadOutput) = Unit
        override suspend fun discardPending(downloadId: DownloadId): Boolean = true
        override suspend fun removePublished(localReference: String): Boolean = true
        override suspend fun finalizeRecoveredPublished(downloadId: DownloadId, descriptor: String, localReference: String): Boolean {
            finalizeCalls += 1
            return finalizeResult && downloadId.value == "recording-recording-1" && descriptor == DESCRIPTOR && localReference == REFERENCE
        }
    }

    private companion object {
        const val DESCRIPTOR = "{\"kind\":\"private-file\",\"reference\":\"/app/pending/recording-1.part\"}"
        const val REFERENCE = "file:///app/completed/recording.ts"
    }
}
