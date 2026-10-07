package app.ownplay.mobile.feature.live.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceRepository
import app.ownplay.mobile.sources.domain.SourceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingSourceRemovalInstrumentedTest {

    @Test
    fun sourceRemovalCancelsFutureRecordingAndBlocksConcurrentReschedule() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = SourceId("recording-source")
        val sourceDao = InMemorySourceDao(source(sourceId))
        val repository = InMemoryRecordingRepository(listOf(recording(sourceId, "future")))
        val scheduler = AndroidLiveRecordingScheduler(context, repository)
        val sourceRepository = RecordingAwareSourceRepository(
            delegate = RemovalSourceRepository(sourceDao),
            sourceDao = sourceDao,
            recordings = repository,
            scheduler = scheduler,
        )

        assertTrue(sourceRepository.removeSource(sourceId))
        assertEquals(LiveRecordingStatus.CANCELLED, repository.get("future")?.status)
        assertEquals(
            LiveRecordingScheduleResult.SOURCE_UNAVAILABLE,
            scheduler.schedule(recording(sourceId, "late")),
        )
    }

    @Test
    fun completedLocalRecordingIsPreservedWhenItsSourceIsRemoved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = SourceId("completed-recording-source")
        val sourceDao = InMemorySourceDao(source(sourceId))
        val saved = recording(sourceId, "saved").copy(
            status = LiveRecordingStatus.COMPLETED,
            localReference = "content://media/external/downloads/recording",
        )
        val repository = InMemoryRecordingRepository(listOf(saved))
        val sourceRepository = RecordingAwareSourceRepository(
            delegate = RemovalSourceRepository(sourceDao),
            sourceDao = sourceDao,
            recordings = repository,
            scheduler = AndroidLiveRecordingScheduler(context, repository),
        )

        assertTrue(sourceRepository.removeSource(sourceId))

        assertEquals(saved, repository.get("saved"))
    }

    private fun recording(sourceId: SourceId, recordingId: String): LiveRecording {
        val start = System.currentTimeMillis() / 1_000L + 3_600L
        return LiveRecording(
            recordingId = recordingId,
            sourceId = sourceId.value,
            channelId = "channel-$recordingId",
            channelName = "Channel",
            title = "Program",
            startEpochSeconds = start,
            endEpochSeconds = start + 1_800L,
            deadlineEpochSeconds = start + 1_800L,
            status = LiveRecordingStatus.SCHEDULED,
        )
    }

    private fun source(sourceId: SourceId) = SourceEntity(
        sourceId = sourceId.value,
        displayName = "Test source",
        type = "XTREAM",
        baseLocator = "https://provider.invalid",
        credentialReference = sourceId.value,
        enabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private class InMemoryRecordingRepository(initial: List<LiveRecording>) : LiveRecordingRepository {
    private val mutableRecordings = MutableStateFlow(initial)
    override val recordings = mutableRecordings.asStateFlow()

    override fun get(recordingId: String): LiveRecording? =
        mutableRecordings.value.firstOrNull { it.recordingId == recordingId }

    override fun put(recording: LiveRecording): Boolean {
        mutableRecordings.value = mutableRecordings.value
            .filterNot { it.recordingId == recording.recordingId } + recording
        return true
    }

    override fun update(recordingId: String, transform: (LiveRecording) -> LiveRecording): Boolean {
        val current = get(recordingId) ?: return false
        return put(transform(current))
    }

    override fun remove(recordingId: String): Boolean {
        mutableRecordings.value = mutableRecordings.value.filterNot { it.recordingId == recordingId }
        return true
    }
}

private class InMemorySourceDao(initial: SourceEntity) : SourceDao {
    private var row: SourceEntity? = initial
    override fun observeAll(): Flow<List<SourceEntity>> = MutableStateFlow(listOfNotNull(row))
    override suspend fun get(sourceId: String): SourceEntity? = row?.takeIf { it.sourceId == sourceId }
    override suspend fun getAll(): List<SourceEntity> = listOfNotNull(row)
    override suspend fun insert(entity: SourceEntity) { row = entity }
    override suspend fun update(entity: SourceEntity) { row = entity }
    override suspend fun delete(sourceId: String): Int {
        if (row?.sourceId != sourceId) return 0
        row = null
        return 1
    }
}

private class RemovalSourceRepository(
    private val sourceDao: SourceDao,
) : SourceRepository {
    override fun observeSources(): Flow<List<SourceSummary>> = MutableStateFlow(emptyList())
    override fun observeActiveSource(): Flow<SourceSummary?> = MutableStateFlow(null)
    override suspend fun addSource(input: SourceInput): SourceMutationResult = error("unused")
    override suspend fun reconnectSource(sourceId: SourceId, input: SourceReconnectInput): SourceMutationResult = error("unused")
    override suspend fun setActiveSource(sourceId: SourceId): Boolean = error("unused")
    override suspend fun clearActiveSource(): Boolean = error("unused")
    override suspend fun renameSource(sourceId: SourceId, displayName: String): SourceMutationResult = error("unused")
    override suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult = error("unused")
    override suspend fun removeSource(sourceId: SourceId): Boolean = sourceDao.delete(sourceId.value) == 1
}
