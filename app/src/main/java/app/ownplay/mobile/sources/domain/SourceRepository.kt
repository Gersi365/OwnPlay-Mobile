package app.ownplay.mobile.sources.domain

import kotlinx.coroutines.flow.Flow

interface SourceRepository {
    fun observeSources(): Flow<List<SourceSummary>>

    fun observeActiveSource(): Flow<SourceSummary?>

    suspend fun addSource(input: SourceInput): SourceMutationResult

    suspend fun reconnectSource(
        sourceId: SourceId,
        input: SourceReconnectInput,
    ): SourceMutationResult

    suspend fun setActiveSource(sourceId: SourceId): Boolean

    suspend fun clearActiveSource(): Boolean

    suspend fun renameSource(
        sourceId: SourceId,
        displayName: String,
    ): SourceMutationResult

    suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult

    /** Skips a background refresh when a manual or scheduled refresh already owns the source. */
    suspend fun refreshSourceIfIdle(sourceId: SourceId): SourceRefreshResult = refreshSource(sourceId)

    suspend fun removeSource(sourceId: SourceId): Boolean
}

sealed interface SourceRefreshResult {
    data object Success : SourceRefreshResult

    data class Partial(
        val failedSections: Set<String>,
    ) : SourceRefreshResult

    data object Skipped : SourceRefreshResult

    data class Failure(
        val category: SourceRefreshFailureCategory,
        val safeMessage: String? = null,
    ) : SourceRefreshResult
}

enum class SourceRefreshFailureCategory {
    AUTHENTICATION,
    NETWORK,
    TIMEOUT,
    INVALID_PAYLOAD,
    PROVIDER,
    TRANSIENT_PROVIDER,
    STORAGE,
    UNKNOWN,
}
