package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.data.prefs.RestoredActiveSourceIntentStore
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationRejection
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceRepository
import app.ownplay.mobile.sources.domain.SourceSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoredActiveSourceAwareSourceRepositoryTest {
    @Test
    fun successfulReconnectActivatesPendingIntendedSourceAndClearsIntent() = runBlocking {
        val intent = FakeIntentStore("source-a")
        val delegate = FakeSourceRepository()
        val repository = RestoredActiveSourceAwareSourceRepository(delegate, intent)

        val result = repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.Xtream("https://example.com", "user", "password"),
        )

        assertTrue(result is SourceMutationResult.Success)
        assertEquals(listOf("source-a"), delegate.activeSelections)
        assertNull(intent.value)
    }

    @Test
    fun failedReconnectPreservesPendingIntent() = runBlocking {
        val intent = FakeIntentStore("source-a")
        val delegate = FakeSourceRepository(
            reconnectResult = SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CREDENTIALS),
        )
        val repository = RestoredActiveSourceAwareSourceRepository(delegate, intent)

        repository.reconnectSource(
            SourceId("source-a"),
            SourceReconnectInput.Xtream("https://example.com", "user", "wrong"),
        )

        assertTrue(delegate.activeSelections.isEmpty())
        assertEquals("source-a", intent.value)
    }

    @Test
    fun explicitNoActiveSelectionSupersedesRestoredIntent() = runBlocking {
        val intent = FakeIntentStore("source-a")
        val delegate = FakeSourceRepository()
        val repository = RestoredActiveSourceAwareSourceRepository(delegate, intent)

        assertTrue(repository.clearActiveSource())

        assertEquals(1, delegate.clearActiveCalls)
        assertNull(intent.value)
    }

    @Test
    fun explicitActiveSelectionSupersedesRestoredIntent() = runBlocking {
        val intent = FakeIntentStore("source-a")
        val delegate = FakeSourceRepository()
        val repository = RestoredActiveSourceAwareSourceRepository(delegate, intent)

        assertTrue(repository.setActiveSource(SourceId("source-b")))

        assertEquals(listOf("source-b"), delegate.activeSelections)
        assertNull(intent.value)
    }
}

private class FakeIntentStore(initial: String?) : RestoredActiveSourceIntentStore {
    private val state = MutableStateFlow(initial)
    var value: String?
        get() = state.value
        set(value) { state.value = value }

    override val intendedSourceId: Flow<String?> = state
    override suspend fun currentIntendedSourceId(): String? = state.value
    override suspend fun setIntendedSourceId(sourceId: String?) {
        state.value = sourceId
    }
}

private class FakeSourceRepository(
    private val reconnectResult: SourceMutationResult =
        SourceMutationResult.Success(SourceId("source-a")),
) : SourceRepository {
    val activeSelections = mutableListOf<String>()
    var clearActiveCalls = 0

    override fun observeSources(): Flow<List<SourceSummary>> = flowOf(emptyList())
    override fun observeActiveSource(): Flow<SourceSummary?> = flowOf(null)
    override suspend fun addSource(input: SourceInput): SourceMutationResult =
        SourceMutationResult.Success(SourceId("source-added"))

    override suspend fun reconnectSource(
        sourceId: SourceId,
        input: SourceReconnectInput,
    ): SourceMutationResult = reconnectResult

    override suspend fun setActiveSource(sourceId: SourceId): Boolean {
        activeSelections += sourceId.value
        return true
    }

    override suspend fun clearActiveSource(): Boolean {
        clearActiveCalls += 1
        return true
    }

    override suspend fun renameSource(
        sourceId: SourceId,
        displayName: String,
    ): SourceMutationResult = SourceMutationResult.Success(sourceId)

    override suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult =
        SourceRefreshResult.Success

    override suspend fun removeSource(sourceId: SourceId): Boolean = true
}
