package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.data.prefs.RestoredActiveSourceIntentStore
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRepository

internal class RestoredActiveSourceAwareSourceRepository(
    private val delegate: SourceRepository,
    private val intendedActiveSourceStore: RestoredActiveSourceIntentStore,
) : SourceRepository by delegate {
    override suspend fun reconnectSource(
        sourceId: SourceId,
        input: SourceReconnectInput,
    ): SourceMutationResult {
        val result = delegate.reconnectSource(sourceId, input)
        if (
            result is SourceMutationResult.Success &&
            intendedActiveSourceStore.currentIntendedSourceId() == sourceId.value &&
            delegate.setActiveSource(sourceId)
        ) {
            intendedActiveSourceStore.setIntendedSourceId(null)
        }
        return result
    }

    override suspend fun setActiveSource(sourceId: SourceId): Boolean {
        val changed = delegate.setActiveSource(sourceId)
        if (changed) {
            intendedActiveSourceStore.setIntendedSourceId(null)
        }
        return changed
    }

    override suspend fun clearActiveSource(): Boolean {
        val changed = delegate.clearActiveSource()
        if (changed) {
            intendedActiveSourceStore.setIntendedSourceId(null)
        }
        return changed
    }

    override suspend fun removeSource(sourceId: SourceId): Boolean {
        val removed = delegate.removeSource(sourceId)
        if (
            removed &&
            intendedActiveSourceStore.currentIntendedSourceId() == sourceId.value
        ) {
            intendedActiveSourceStore.setIntendedSourceId(null)
        }
        return removed
    }
}
