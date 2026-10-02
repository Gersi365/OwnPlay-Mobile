package app.ownplay.mobile.feature.settings.data

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceRepository
import app.ownplay.mobile.sources.domain.SourceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshScheduleAwareSourceRepositoryTest {
    private val sourceId = SourceId("source-a")

    @Test
    fun successfulSourceRemovalClearsRefreshSchedule() = runBlocking {
        val cleanup = FakeCleanup()
        val repository = RefreshScheduleAwareSourceRepository(
            delegate = FakeSourceRepository(removeResult = true),
            scheduleCleanup = cleanup,
        )

        assertTrue(repository.removeSource(sourceId))
        assertEquals(listOf(sourceId), cleanup.cleared)
    }

    @Test
    fun failedSourceRemovalDoesNotClearRefreshSchedule() = runBlocking {
        val cleanup = FakeCleanup()
        val repository = RefreshScheduleAwareSourceRepository(
            delegate = FakeSourceRepository(removeResult = false),
            scheduleCleanup = cleanup,
        )

        assertFalse(repository.removeSource(sourceId))
        assertTrue(cleanup.cleared.isEmpty())
    }
}

private class FakeCleanup : SourceRefreshScheduleCleanup {
    val cleared = mutableListOf<SourceId>()
    override suspend fun clearSource(sourceId: SourceId) { cleared += sourceId }
}

private class FakeSourceRepository(
    private val removeResult: Boolean,
) : SourceRepository {
    override fun observeSources(): Flow<List<SourceSummary>> = flowOf(emptyList())
    override fun observeActiveSource(): Flow<SourceSummary?> = flowOf(null)
    override suspend fun addSource(input: SourceInput): SourceMutationResult = error("unused")
    override suspend fun reconnectSource(sourceId: SourceId, input: SourceReconnectInput): SourceMutationResult = error("unused")
    override suspend fun setActiveSource(sourceId: SourceId): Boolean = error("unused")
    override suspend fun clearActiveSource(): Boolean = error("unused")
    override suspend fun renameSource(sourceId: SourceId, displayName: String): SourceMutationResult = error("unused")
    override suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult = error("unused")
    override suspend fun removeSource(sourceId: SourceId): Boolean = removeResult
}
