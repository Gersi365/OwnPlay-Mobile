package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPictureInPicturePolicyTest {
    private val sourceId = SourceId("source-a")

    @Test
    fun eligibilityRequiresPreparedStateAndSupportedPresentationForEachMediaKind() {
        val live = PlaybackTarget.LiveChannel(sourceId, "channel-a")
        val catchUp = PlaybackTarget.CatchUp(
            sourceId = sourceId,
            channelId = "channel-a",
            programId = "program-a",
            title = "Program A",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
        )
        val movie = PlaybackTarget.Movie(sourceId, "movie-a")

        listOf(
            PlaybackPresentation.PREVIEW,
            PlaybackPresentation.FULLSCREEN,
            PlaybackPresentation.PICTURE_IN_PICTURE,
        ).forEach { presentation ->
            assertTrue(PlaybackPictureInPicturePolicy.isEligible(prepared(live, presentation)))
            assertTrue(PlaybackPictureInPicturePolicy.isEligible(prepared(catchUp, presentation)))
        }

        assertFalse(
            PlaybackPictureInPicturePolicy.isEligible(
                prepared(movie, PlaybackPresentation.PREVIEW),
            ),
        )
        assertTrue(
            PlaybackPictureInPicturePolicy.isEligible(
                prepared(movie, PlaybackPresentation.FULLSCREEN),
            ),
        )
        assertTrue(
            PlaybackPictureInPicturePolicy.isEligible(
                prepared(movie, PlaybackPresentation.PICTURE_IN_PICTURE),
            ),
        )

        assertFalse(
            PlaybackPictureInPicturePolicy.isEligible(
                PlaybackSessionState(
                    target = live,
                    presentation = PlaybackPresentation.FULLSCREEN,
                    readiness = PlaybackReadiness.PREPARING,
                ),
            ),
        )
        assertFalse(PlaybackPictureInPicturePolicy.isEligible(PlaybackSessionState()))
    }

    @Test
    fun automaticPictureInPictureRequiresBothUserPreferenceAndPlaybackEligibility() {
        val state = prepared(
            PlaybackTarget.LiveChannel(sourceId, "channel-a"),
            PlaybackPresentation.PREVIEW,
        )

        assertTrue(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                preferences = PlaybackPreferences(automaticPictureInPicture = true),
                state = state,
            ),
        )
        assertFalse(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                preferences = PlaybackPreferences(automaticPictureInPicture = false),
                state = state,
            ),
        )
        assertFalse(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                preferences = PlaybackPreferences(automaticPictureInPicture = true),
                state = state.copy(readiness = PlaybackReadiness.UNAVAILABLE),
            ),
        )
    }

    @Test
    fun pipPlayPauseActionIsLimitedToPreparedCatchUpAndLibraryPlayback() {
        val live = prepared(
            PlaybackTarget.LiveChannel(sourceId, "channel-a"),
            PlaybackPresentation.PICTURE_IN_PICTURE,
        )
        val catchUp = prepared(
            PlaybackTarget.CatchUp(
                sourceId = sourceId,
                channelId = "channel-a",
                programId = "program-a",
                title = "Program A",
                startEpochSeconds = 100L,
                endEpochSeconds = 200L,
            ),
            PlaybackPresentation.PICTURE_IN_PICTURE,
        )
        val movie = prepared(
            PlaybackTarget.Movie(sourceId, "movie-a"),
            PlaybackPresentation.PICTURE_IN_PICTURE,
        )

        assertFalse(PlaybackPictureInPictureActionPolicy.showsPlayPause(live))
        assertTrue(PlaybackPictureInPictureActionPolicy.showsPlayPause(catchUp))
        assertTrue(PlaybackPictureInPictureActionPolicy.showsPlayPause(movie))
        assertFalse(
            PlaybackPictureInPictureActionPolicy.showsPlayPause(
                movie.copy(readiness = PlaybackReadiness.PREPARING),
            ),
        )
    }

    private fun prepared(
        target: PlaybackTarget,
        presentation: PlaybackPresentation,
    ): PlaybackSessionState =
        PlaybackSessionState(
            target = target,
            presentation = presentation,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = true,
        )
}
