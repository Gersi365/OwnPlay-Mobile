package app.ownplay.player.ui.tv

import app.ownplay.player.playback.PlaybackFailure
import app.ownplay.player.playback.PlaybackFailureCategory
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackRequest
import app.ownplay.player.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackLifecyclePolicyTest {
    @Test
    fun activePlaybackSuspendsWhenActivityStops() {
        listOf(
            PlaybackMediaKind.LIVE,
            PlaybackMediaKind.MOVIE,
            PlaybackMediaKind.SERIES_EPISODE,
        ).forEach { kind ->
            val request = request(kind)
            assertEquals(
                TvBackgroundPlaybackAction.SUSPEND,
                TvPlaybackLifecyclePolicy.backgroundAction(PlaybackState.Loading(request)),
            )
            assertEquals(
                TvBackgroundPlaybackAction.SUSPEND,
                TvPlaybackLifecyclePolicy.backgroundAction(PlaybackState.Playing(request)),
            )
            assertEquals(
                TvBackgroundPlaybackAction.SUSPEND,
                TvPlaybackLifecyclePolicy.backgroundAction(PlaybackState.Paused(request)),
            )
        }
    }

    @Test
    fun idleAndFailedPlaybackNeedNoBackgroundAction() {
        assertEquals(
            TvBackgroundPlaybackAction.NONE,
            TvPlaybackLifecyclePolicy.backgroundAction(PlaybackState.Idle),
        )
        assertEquals(
            TvBackgroundPlaybackAction.NONE,
            TvPlaybackLifecyclePolicy.backgroundAction(
                PlaybackState.Failed(
                    request(PlaybackMediaKind.LIVE),
                    PlaybackFailure(PlaybackFailureCategory.UNKNOWN),
                ),
            ),
        )
    }

    private fun request(kind: PlaybackMediaKind) = PlaybackRequest(
        sourceId = "source",
        channelId = "content",
        mediaKind = kind,
        providerStreamId = if (kind == PlaybackMediaKind.SERIES_EPISODE) 7 else null,
    )
}
