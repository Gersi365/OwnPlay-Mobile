package app.ownplay.mobile.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveActivationReducerTest {
    private val first = LiveSelection(sourceId = "source-a", channelId = "channel-1")
    private val second = LiveSelection(sourceId = "source-a", channelId = "channel-2")

    @Test
    fun `different channel opens preview`() {
        assertEquals(
            LiveActivationDecision.OpenPreview(second),
            LiveActivationReducer.activate(first, LivePresentation.PREVIEW, second),
        )
    }

    @Test
    fun `same previewed channel opens fullscreen`() {
        assertEquals(
            LiveActivationDecision.OpenFullscreen(first),
            LiveActivationReducer.activate(first, LivePresentation.PREVIEW, first),
        )
    }

    @Test
    fun `same channel outside preview opens preview`() {
        assertEquals(
            LiveActivationDecision.OpenPreview(first),
            LiveActivationReducer.activate(first, LivePresentation.BROWSING, first),
        )
    }
}
