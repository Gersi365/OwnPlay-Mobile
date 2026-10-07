package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test
    fun periodicCheckpointCoversPlayingVodAndCatchUpIncludingPip() {
        val movie = PlaybackSessionState(
            target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a"),
            presentation = PlaybackPresentation.PICTURE_IN_PICTURE,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = true,
        )
        val catchUp = PlaybackSessionState(
            target = PlaybackTarget.CatchUp(
                sourceId = SourceId("source-a"),
                channelId = "channel-a",
                programId = "program-a",
                title = "Program",
                startEpochSeconds = 100L,
                endEpochSeconds = 200L,
            ),
            presentation = PlaybackPresentation.PICTURE_IN_PICTURE,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = true,
        )

        assertTrue(PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(movie))
        assertTrue(PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(catchUp))
    }

    @Test
    fun periodicCheckpointSkipsLivePausedAndUnpreparedSessions() {
        val live = PlaybackSessionState(
            target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"),
            presentation = PlaybackPresentation.FULLSCREEN,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = true,
        )
        val movie = PlaybackSessionState(
            target = PlaybackTarget.Movie(SourceId("source-a"), "movie-a"),
            presentation = PlaybackPresentation.FULLSCREEN,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = false,
        )

        assertFalse(PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(live))
        assertFalse(PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(movie))
        assertFalse(
            PlaybackPeriodicCheckpointPolicy.shouldCheckpoint(
                movie.copy(playWhenReady = true, readiness = PlaybackReadiness.PREPARING),
            ),
        )
    }
}
