package app.ownplay.player

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyncPublicationContractTest {
    @Test
    fun sourceSpecificStatesArePublishedSynchronously() {
        val source = sourceText("src/main/java/app/ownplay/player/OwnPlayAppRuntime.kt")
        assertTrue(source.contains("private fun publishSourceState(state: SourceSyncState)"))
        assertFalse(source.contains("_sourceSyncState.collect(::rememberSourceState)"))

        val staleRefresh = sourceBlockAfter(
            source,
            "private suspend fun refreshSourceIfStale(sourceId: String)",
        )
        assertTrue(staleRefresh.contains("runReadyRefresh("))
        assertTrue(staleRefresh.contains("onlyIfStale = true"))
        assertFalse(staleRefresh.contains("refreshSource(sourceId)"))

        val lifecycle = sourceBlockAfter(source, "private suspend fun runReadyRefresh(")
        assertTrue(lifecycle.contains("refreshMutex.withLock"))
        assertTrue(lifecycle.contains("if (onlyIfStale)"))
        assertTrue(lifecycle.contains("database.refreshStateDao().get(sourceId)"))
        assertTrue(lifecycle.contains("shouldRefreshSource("))
        assertTrue(lifecycle.contains("executeReadyRefreshLocked(sourceId)"))
        val freshnessIndex = lifecycle.indexOf("shouldRefreshSource(")
        val executionIndex = lifecycle.indexOf("executeReadyRefreshLocked(sourceId)")
        assertTrue(freshnessIndex >= 0 && freshnessIndex < executionIndex)

        val execution = sourceBlockAfter(
            source,
            "private suspend fun executeReadyRefreshLocked(sourceId: String)",
        )
        assertTrue(execution.contains("markRefreshRunning(sourceId)"))
        assertTrue(execution.contains("refreshSourcePipelineLocked(sourceId)"))
        assertTrue(execution.contains("markRefreshSucceeded(sourceId)"))
        assertTrue(execution.contains("markRefreshFailed(sourceId, outcome.failure.toString())"))
        assertFalse(execution.contains("refreshMutex.withLock"))
        val runningIndex = execution.indexOf("markRefreshRunning(sourceId)")
        val pipelineIndex = execution.indexOf("refreshSourcePipelineLocked(sourceId)")
        val successIndex = execution.indexOf("markRefreshSucceeded(sourceId)")
        assertTrue(runningIndex >= 0 && runningIndex < pipelineIndex)
        assertTrue(pipelineIndex >= 0 && pipelineIndex < successIndex)

        val manualRefresh = sourceBlockAfter(source, "suspend fun refreshSource(sourceId: String)")
        assertTrue(manualRefresh.contains("runReadyRefresh("))
        assertTrue(manualRefresh.contains("onlyIfStale = false"))

        val pipeline = sourceBlockAfter(source, "private suspend fun refreshSourcePipelineLocked(")
        assertTrue(pipeline.contains("publishSourceState("))
        assertTrue(pipeline.contains("ReadyRefreshOutcome.ChannelsFailed(failure)"))
        assertTrue(pipeline.contains("ReadyRefreshOutcome.Succeeded"))
        assertFalse(pipeline.contains("refreshMutex.withLock"))
        assertFalse(pipeline.contains("_sourceSyncState.value = SourceSyncState("))

        val unexpectedFailure = sourceBlockAfter(
            source,
            "private suspend fun publishUnexpectedRefreshFailure(sourceId: String)",
        )
        assertTrue(unexpectedFailure.contains("val current = _sourceSyncStates.value[sourceId]"))
        assertFalse(unexpectedFailure.contains("val current = _sourceSyncState.value"))

        val epg = sourceBlockAfter(source, "private suspend fun loadEpgAfterChannels(")
        assertTrue(epg.contains("publishSourceState("))
        assertTrue(epg.contains("epgRepository.refreshSource(sourceId)"))
        assertFalse(epg.contains("_sourceSyncState.value = if (epg == null)"))
        val cancellationIndex = epg.indexOf("catch (cancelled: CancellationException)")
        val genericFailureIndex = epg.indexOf("catch (_: Exception)")
        assertTrue(cancellationIndex >= 0 && cancellationIndex < genericFailureIndex)
        assertTrue(
            epg.substring(cancellationIndex, genericFailureIndex).contains("throw cancelled"),
        )
    }
}
