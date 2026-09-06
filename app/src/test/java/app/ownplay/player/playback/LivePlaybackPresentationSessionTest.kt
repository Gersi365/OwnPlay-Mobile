package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaybackPresentationSessionTest {
    @Test
    fun previewStateIsRetainedUntilExplicitlyChanged() {
        val session = LivePlaybackPresentationSession()
        val selection = selection(channelId = "one")

        session.showPreview(selection)

        assertEquals(selection, session.state.value.selection)
        assertEquals(LivePlaybackPresentationSurface.PREVIEW, session.state.value.surface)
        assertNull(session.state.value.fullscreenSelection)
        assertNull(session.state.value.fullscreenEntryReason)
    }

    @Test
    fun fullscreenStateRetainsRotationReasonForHostRecreation() {
        val session = LivePlaybackPresentationSession()
        val selection = selection(channelId = "one")

        session.showFullscreen(
            selection = selection,
            entryReason = LiveFullscreenEntryReason.ROTATION,
        )

        assertEquals(selection, session.state.value.fullscreenSelection)
        assertEquals(LivePlaybackPresentationSurface.FULLSCREEN, session.state.value.surface)
        assertEquals(LiveFullscreenEntryReason.ROTATION, session.state.value.fullscreenEntryReason)
    }

    @Test
    fun navigationReplacesSelectionWithoutChangingPresentation() {
        val session = LivePlaybackPresentationSession()
        session.showFullscreen(
            selection = selection(channelId = "one"),
            entryReason = LiveFullscreenEntryReason.USER,
        )
        val next = selection(channelId = "two")

        session.replaceSelection(next)

        assertEquals(next, session.state.value.selection)
        assertEquals(LivePlaybackPresentationSurface.FULLSCREEN, session.state.value.surface)
        assertEquals(LiveFullscreenEntryReason.USER, session.state.value.fullscreenEntryReason)
    }

    @Test
    fun returningToPreviewDropsFullscreenOnlyMetadata() {
        val session = LivePlaybackPresentationSession()
        val selection = selection(channelId = "one")
        session.showFullscreen(selection, LiveFullscreenEntryReason.USER)

        session.showPreview(selection)

        assertEquals(LivePlaybackPresentationSurface.PREVIEW, session.state.value.surface)
        assertNull(session.state.value.fullscreenEntryReason)
    }

    @Test
    fun clearRemovesTransientPresentation() {
        val session = LivePlaybackPresentationSession()
        session.showPreview(selection(channelId = "one"))

        session.clear()

        assertNull(session.state.value.selection)
        assertNull(session.state.value.surface)
        assertNull(session.state.value.fullscreenEntryReason)
    }

    private fun selection(channelId: String): LivePlaybackSelection = LivePlaybackSelection(
        request = PlaybackRequest(
            sourceId = "source",
            channelId = channelId,
        ),
        displayName = "Channel $channelId",
    )
}
