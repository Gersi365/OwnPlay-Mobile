package app.ownplay.player.ui

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceSyncFailure
import app.ownplay.player.source.SourceSyncStage
import app.ownplay.player.source.SourceSyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSyncStatusTest {
    @Test
    fun newSourceFailureDoesNotClaimExistingChannelsAreAvailable() {
        assertEquals(
            "Could not load channels. Authentication failed. Check your username and password.",
            sourceSyncStatus(
                SourceSyncState(
                    stage = SourceSyncStage.ChannelsFailed,
                    failure = SourceSyncFailure.Source(SourceError.AuthenticationFailed),
                ),
            ),
        )
    }

    @Test
    fun refreshFailurePreservesExistingCatalogAndOffersRecoveryContext() {
        assertEquals(
            "Refresh failed. No network connection. Check your connection and try again. " +
                "Your existing channels are still available.",
            sourceSyncStatus(
                SourceSyncState(
                    stage = SourceSyncStage.ChannelsFailed,
                    channelCount = 42,
                    failure = SourceSyncFailure.Source(SourceError.NetworkUnavailable),
                ),
            ),
        )
    }

    @Test
    fun operationalFailureHasUsefulExplanation() {
        assertEquals(
            "Could not load channels. Secure storage is unavailable. Try again.",
            sourceSyncStatus(
                SourceSyncState(
                    stage = SourceSyncStage.ChannelsFailed,
                    failure = SourceSyncFailure.SecureStorage,
                ),
            ),
        )
    }

    @Test
    fun missingFailureCauseDoesNotLeaveAwkwardSpacing() {
        assertEquals(
            "Could not load channels.",
            sourceSyncStatus(SourceSyncState(stage = SourceSyncStage.ChannelsFailed)),
        )
        assertEquals(
            "Refresh failed. Your existing channels are still available.",
            sourceSyncStatus(
                SourceSyncState(
                    stage = SourceSyncStage.ChannelsFailed,
                    channelCount = 7,
                ),
            ),
        )
    }

    @Test
    fun epgFailureKeepsChannelsAvailable() {
        assertEquals(
            "Channels are ready. EPG could not be refreshed.",
            sourceSyncStatus(SourceSyncState(stage = SourceSyncStage.EpgFailed)),
        )
    }
}
