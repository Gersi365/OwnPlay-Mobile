package app.ownplay.player.source

sealed interface SourceSyncStage {
    data object Idle : SourceSyncStage
    data object LoadingChannels : SourceSyncStage
    data object LoadingEpg : SourceSyncStage
    data object Ready : SourceSyncStage
    data object ChannelsFailed : SourceSyncStage
    data object EpgFailed : SourceSyncStage
}

sealed interface SourceSyncFailure {
    data class Source(val error: SourceError) : SourceSyncFailure
    data object InvalidInput : SourceSyncFailure
    data object SecureStorage : SourceSyncFailure
    data object Persistence : SourceSyncFailure
    data object CatalogImport : SourceSyncFailure
    data object Unexpected : SourceSyncFailure
}

data class SourceSyncState(
    val sourceId: String? = null,
    val sourceName: String? = null,
    val stage: SourceSyncStage = SourceSyncStage.Idle,
    val channelCount: Int = 0,
    val epgChannelCount: Int = 0,
    val failure: SourceSyncFailure? = null,
)
