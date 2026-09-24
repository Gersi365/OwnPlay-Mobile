package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadId
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

class DownloadAwareSourceRepositoryTest {
    @Test
    fun successfulRemovalQuiescesBeforeDeleteAndFinalizesAfterward() = runBlocking {
        val events = mutableListOf<String>()
        val delegate = FakeSourceRepository(removeResult = true, events = events)
        val coordinator = FakeRemovalCoordinator(
            plan = SourceRemovalDownloadPlan(listOf(DownloadId("download:a"))),
            events = events,
        )
        val repository = DownloadAwareSourceRepository(delegate, coordinator)

        assertTrue(repository.removeSource(SourceId("source-a")))
        assertEquals(
            listOf("capture", "quiesce", "delegate-remove", "finalize"),
            events,
        )
        assertEquals(listOf(SourceId("source-a")), coordinator.capturedSources)
        assertEquals(1, delegate.removeCalls)
    }

    @Test
    fun captureFailureBlocksSourceRemoval() = runBlocking {
        val delegate = FakeSourceRepository(removeResult = true)
        val coordinator = FakeRemovalCoordinator(plan = null)
        val repository = DownloadAwareSourceRepository(delegate, coordinator)

        assertFalse(repository.removeSource(SourceId("source-a")))
        assertEquals(0, delegate.removeCalls)
        assertEquals(0, coordinator.quiesceCalls)
        assertEquals(0, coordinator.finalizeCalls)
    }

    @Test
    fun quiesceFailureBlocksSourceRemoval() = runBlocking {
        val delegate = FakeSourceRepository(removeResult = true)
        val coordinator = FakeRemovalCoordinator(
            plan = SourceRemovalDownloadPlan(listOf(DownloadId("download:a"))),
            quiesceResult = false,
        )
        val repository = DownloadAwareSourceRepository(delegate, coordinator)

        assertFalse(repository.removeSource(SourceId("source-a")))
        assertEquals(0, delegate.removeCalls)
        assertEquals(1, coordinator.quiesceCalls)
        assertEquals(
            listOf(SourceRemovalDownloadPlan(listOf(DownloadId("download:a")))),
            coordinator.restoredPlans,
        )
        assertEquals(0, coordinator.finalizeCalls)
    }

    @Test
    fun cleanupFailureAfterCommittedSourceRemovalDoesNotReportFalseFailure() = runBlocking {
        val delegate = FakeSourceRepository(removeResult = true)
        val coordinator = FakeRemovalCoordinator(
            plan = SourceRemovalDownloadPlan(listOf(DownloadId("download:a"))),
            throwOnFinalize = true,
        )
        val repository = DownloadAwareSourceRepository(delegate, coordinator)

        assertTrue(repository.removeSource(SourceId("source-a")))
        assertEquals(1, delegate.removeCalls)
        assertEquals(1, coordinator.quiesceCalls)
        assertEquals(1, coordinator.finalizeCalls)
    }

    @Test
    fun failedSourceRemovalRestoresPreviouslyQuiescedDownloadWork() = runBlocking {
        val delegate = FakeSourceRepository(removeResult = false)
        val plan = SourceRemovalDownloadPlan(listOf(DownloadId("download:a")))
        val coordinator = FakeRemovalCoordinator(plan)
        val repository = DownloadAwareSourceRepository(delegate, coordinator)

        assertFalse(repository.removeSource(SourceId("source-a")))
        assertEquals(1, delegate.removeCalls)
        assertEquals(listOf(plan), coordinator.restoredPlans)
        assertEquals(0, coordinator.finalizeCalls)
    }
}

private class FakeRemovalCoordinator(
    private val plan: SourceRemovalDownloadPlan?,
    private val quiesceResult: Boolean = true,
    private val throwOnFinalize: Boolean = false,
    private val events: MutableList<String>? = null,
) : SourceRemovalDownloadCoordinator {
    val capturedSources = mutableListOf<SourceId>()
    val restoredPlans = mutableListOf<SourceRemovalDownloadPlan>()
    var quiesceCalls = 0
    var finalizeCalls = 0

    override suspend fun capture(sourceId: SourceId): SourceRemovalDownloadPlan? {
        events?.add("capture")
        capturedSources += sourceId
        return plan
    }

    override suspend fun quiesce(plan: SourceRemovalDownloadPlan): Boolean {
        events?.add("quiesce")
        quiesceCalls += 1
        return quiesceResult
    }

    override suspend fun restore(plan: SourceRemovalDownloadPlan) {
        events?.add("restore")
        restoredPlans += plan
    }

    override suspend fun finalize(plan: SourceRemovalDownloadPlan) {
        events?.add("finalize")
        finalizeCalls += 1
        if (throwOnFinalize) error("cleanup failed")
    }
}

private class FakeSourceRepository(
    private val removeResult: Boolean,
    private val events: MutableList<String>? = null,
) : SourceRepository {
    var removeCalls = 0

    override fun observeSources(): Flow<List<SourceSummary>> = flowOf(emptyList())
    override fun observeActiveSource(): Flow<SourceSummary?> = flowOf(null)
    override suspend fun addSource(input: SourceInput): SourceMutationResult = error("unused")
    override suspend fun reconnectSource(sourceId: SourceId, input: SourceReconnectInput): SourceMutationResult = error("unused")
    override suspend fun setActiveSource(sourceId: SourceId): Boolean = error("unused")
    override suspend fun clearActiveSource(): Boolean = error("unused")
    override suspend fun renameSource(sourceId: SourceId, displayName: String): SourceMutationResult = error("unused")
    override suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult = error("unused")
    override suspend fun removeSource(sourceId: SourceId): Boolean {
        events?.add("delegate-remove")
        removeCalls += 1
        return removeResult
    }
}
