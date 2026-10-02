package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreferencesTest {
    private val movieFullscreen = PlaybackSessionState(
        target = PlaybackTarget.Movie(SourceId("source-1"), "movie-1"),
        presentation = PlaybackPresentation.FULLSCREEN,
        readiness = PlaybackReadiness.PREPARED,
    )
    private val livePreview = PlaybackSessionState(
        target = PlaybackTarget.LiveChannel(SourceId("source-1"), "channel-1"),
        presentation = PlaybackPresentation.PREVIEW,
        readiness = PlaybackReadiness.PREPARED,
    )

    @Test
    fun defaultsMatchProductContract() {
        val defaults = PlaybackPreferences()
        assertTrue(defaults.automaticPictureInPicture)
        assertEquals(1f, defaults.playerVolume)
    }

    @Test
    fun automaticPictureInPictureIncludesLivePreviewButNotLibraryPreview() {
        assertTrue(PlaybackAutomaticPictureInPicturePolicy.isEligible(PlaybackPreferences(), movieFullscreen))
        assertTrue(PlaybackAutomaticPictureInPicturePolicy.isEligible(PlaybackPreferences(), livePreview))
        assertFalse(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                PlaybackPreferences(),
                movieFullscreen.copy(presentation = PlaybackPresentation.PREVIEW),
            ),
        )
        assertFalse(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                PlaybackPreferences(automaticPictureInPicture = false),
                livePreview,
            ),
        )
        assertFalse(
            PlaybackAutomaticPictureInPicturePolicy.isEligible(
                PlaybackPreferences(),
                livePreview.copy(readiness = PlaybackReadiness.PREPARING),
            ),
        )
    }
}
