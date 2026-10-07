package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.DownloadStorage
import app.ownplay.mobile.downloads.data.PendingDownloadOutput
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingRemovalPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecordingRemovalManagerTest {
    @Test
    fun metadataOnlyRemoveKeepsCompletedPhysicalFile() = runBlocking {
        val repository = FakeRecordingRepository(completed())
        val storage = FakeDownloadStorage()
        val manager = LiveRecordingRemovalManager(repository, { true }, storage)

        assertTrue(manager.remove("recording", deleteLocalFile = false))
        assertNull(repository.get("recording"))
        assertTrue(storage.removedReferences.isEmpty())
    }

    @Test
    fun checkedPhysicalDeleteFailureLeavesHiddenRetryableTombstone() = runBlocking {
        val recording = completed()
        val repository = FakeRecordingRepository(recording)
        val storage = FakeDownloadStorage(removeResult = false)
        val manager = LiveRecordingRemovalManager(repository, { true }, storage)

        assertFalse(manager.remove("recording", deleteLocalFile = true))
        assertEquals(LiveRecordingStatus.DELETE_PENDING, repository.get("recording")?.status)
        assertFalse(LiveRecordingRemovalPolicy.canRemove(requireNotNull(repository.get("recording")).status))
        assertEquals(listOf(recording.localReference), storage.removedReferences)
    }

    @Test
    fun deletionTombstoneRecoversWhenMetadataRemovalFailsAfterPhysicalDelete() = runBlocking {
        val recording = completed()
        val repository = FakeRecordingRepository(recording, removeFailuresRemaining = 1)
        val storage = FakeDownloadStorage()
        val manager = LiveRecordingRemovalManager(repository, { true }, storage)

        assertFalse(manager.remove("recording", deleteLocalFile = true))
        assertEquals(LiveRecordingStatus.DELETE_PENDING, repository.get("recording")?.status)
        assertEquals(1, storage.removedReferences.size)

        manager.recoverPendingDeletes()
        assertNull(repository.get("recording"))
        assertEquals(listOf(recording.localReference, recording.localReference), storage.removedReferences)
    }

    @Test
    fun failedTombstonePersistenceNeverDeletesTheFile() = runBlocking {
        val recording = completed()
        val repository = FakeRecordingRepository(recording, failDeleteTombstoneWrite = true)
        val storage = FakeDownloadStorage()
        val manager = LiveRecordingRemovalManager(repository, { true }, storage)

        assertFalse(manager.remove("recording", deleteLocalFile = true))
        assertEquals(recording, repository.get("recording"))
        assertTrue(storage.removedReferences.isEmpty())
    }

    @Test
    fun scheduledRemoveCancelsAlarmBeforeRemovingRow() = runBlocking {
        val repository = FakeRecordingRepository(completed().copy(
            status = LiveRecordingStatus.SCHEDULED,
            localReference = null,
        ))
        val cancelled = mutableListOf<String>()
        val manager = LiveRecordingRemovalManager(
            repository = repository,
            cancelScheduled = { id -> cancelled += id; true },
            storage = FakeDownloadStorage(),
        )

        assertTrue(manager.remove("recording", deleteLocalFile = false))
        assertEquals(listOf("recording"), cancelled)
        assertNull(repository.get("recording"))
    }

    private fun completed() = LiveRecording(
        recordingId = "recording",
        sourceId = "source",
        channelId = "channel",
        channelName = "Channel",
        title = "Program",
        startEpochSeconds = 1_000L,
        endEpochSeconds = 2_000L,
        status = LiveRecordingStatus.COMPLETED,
        localReference = "content://media/external/downloads/42",
    )

    private class FakeRecordingRepository(
        initial: LiveRecording,
        private var removeFailuresRemaining: Int = 0,
        private val failDeleteTombstoneWrite: Boolean = false,
    ) : LiveRecordingRepository {
        private val mutable = MutableStateFlow(listOf(initial))
        override val recordings: StateFlow<List<LiveRecording>> = mutable

        override fun get(recordingId: String): LiveRecording? =
            mutable.value.firstOrNull { it.recordingId == recordingId }

        override fun put(recording: LiveRecording): Boolean {
            if (failDeleteTombstoneWrite && recording.status == LiveRecordingStatus.DELETE_PENDING) return false
            mutable.value = mutable.value.filterNot { it.recordingId == recording.recordingId } + recording
            return true
        }

        override fun update(
            recordingId: String,
            transform: (LiveRecording) -> LiveRecording,
        ): Boolean {
            val current = get(recordingId) ?: return false
            return put(transform(current))
        }

        override fun remove(recordingId: String): Boolean {
            if (removeFailuresRemaining > 0) {
                removeFailuresRemaining -= 1
                return false
            }
            mutable.value = mutable.value.filterNot { it.recordingId == recordingId }
            return true
        }
    }

    private class FakeDownloadStorage(
        private val removeResult: Boolean = true,
    ) : DownloadStorage {
        val removedReferences = mutableListOf<String>()

        override suspend fun openPending(
            downloadId: DownloadId,
            media: ResolvedDownloadMedia,
        ): PendingDownloadOutput? = null

        override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? = null
        override suspend fun publish(pending: PendingDownloadOutput): String? = null
        override suspend fun discard(pending: PendingDownloadOutput) = Unit
        override suspend fun discardPending(downloadId: DownloadId): Boolean = true

        override suspend fun removePublished(localReference: String): Boolean {
            removedReferences += localReference
            return removeResult
        }
    }
}
