package app.ownplay.player.ui

import app.ownplay.player.source.SourceSyncFailure
import app.ownplay.player.source.SourceSyncStage
import app.ownplay.player.source.SourceSyncState

internal fun sourceSyncStatus(state: SourceSyncState): String? = when (state.stage) {
    SourceSyncStage.Idle -> null
    SourceSyncStage.LoadingChannels -> "Loading channels…"
    SourceSyncStage.LoadingEpg -> "Channels loaded. Loading EPG…"
    SourceSyncStage.Ready ->
        "Ready • ${state.channelCount} channels • ${state.epgChannelCount} with EPG"
    SourceSyncStage.ChannelsFailed -> {
        val cause = sourceSyncFailureMessage(state.failure)
        if (state.channelCount > 0) {
            listOfNotNull(
                "Refresh failed.",
                cause,
                "Your existing channels are still available.",
            ).joinToString(" ")
        } else {
            listOfNotNull("Could not load channels.", cause).joinToString(" ")
        }
    }
    SourceSyncStage.EpgFailed -> "Channels are ready. EPG could not be refreshed."
}

private fun sourceSyncFailureMessage(failure: SourceSyncFailure?): String? = when (failure) {
    null -> null
    is SourceSyncFailure.Source -> sourceErrorMessage(failure.error)
    SourceSyncFailure.InvalidInput -> "Check the playlist details."
    SourceSyncFailure.SecureStorage -> "Secure storage is unavailable. Try again."
    SourceSyncFailure.Persistence -> "Could not save or read playlist data. Try again."
    SourceSyncFailure.CatalogImport ->
        "OwnPlay could not import this playlist. Check the source and try again."
    SourceSyncFailure.Unexpected -> "Something went wrong while refreshing. Try again."
}
