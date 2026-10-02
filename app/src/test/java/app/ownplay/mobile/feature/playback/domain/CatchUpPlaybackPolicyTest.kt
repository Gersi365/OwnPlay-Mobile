package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpPlaybackPolicyTest {
    private val target = PlaybackTarget.CatchUp(
        sourceId = SourceId("source"),
        channelId = "channel",
        programId = "program",
        title = "Archived program",
        startEpochSeconds = 100L,
        endEpochSeconds = 200L,
    )

    @Test
    fun catchUpStartsInPreviewAndCanEnterPictureInPicture() {
        val preview = PlaybackSessionPolicy.activateCatchUp(
            current = PlaybackSessionState(),
            target = target,
        )

        assertEquals(PlaybackPresentation.PREVIEW, preview.presentation)
        assertEquals(target, preview.target)
        val prepared = preview.copy(readiness = PlaybackReadiness.PREPARED)
        assertTrue(
            PlaybackPictureInPicturePolicy.isEligible(prepared),
        )
        assertTrue(
            PlaybackPictureInPictureActionPolicy.showsPlayPause(prepared),
        )
    }

    @Test
    fun reactivatingSameCatchUpReturnsToPreviewAndClearsNaturalEndMarker() {
        val current = PlaybackSessionState(
            target = target,
            presentation = PlaybackPresentation.FULLSCREEN,
            readiness = PlaybackReadiness.PREPARED,
            playWhenReady = false,
            endedNaturally = true,
        )

        val result = PlaybackSessionPolicy.activateCatchUp(current, target)

        assertEquals(PlaybackPresentation.PREVIEW, result.presentation)
        assertEquals(false, result.endedNaturally)
    }
}
