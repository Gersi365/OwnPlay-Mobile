package app.ownplay.player.ui

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandPlaybackPresentationContractTest {
    @Test
    fun `shared on-demand surface owns custom controls without origin chrome`() {
        val shared = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")

        assertTrue(shared.contains("showNativeController = false"))
        assertTrue(shared.contains("ON_DEMAND_CONTROLS_AUTO_HIDE_MILLIS"))
        assertTrue(shared.contains("Slider("))
        assertTrue(shared.contains("Icons.Filled.PlayArrow"))
        assertTrue(shared.contains("Icons.Filled.Pause"))
        assertFalse(shared.contains("ONLINE"))
        assertFalse(shared.contains("OFFLINE"))
        assertFalse(shared.contains("Local file"))
    }

    @Test
    fun `series and offline use shared presentation instead of native controller chrome`() {
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
        val seriesPlayback = sourceBlockAfter(series, "private fun SeriesPlaybackScreen(")
        val offline = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")
        val offlinePlayback = sourceBlockAfter(offline, "internal fun LibraryPlaybackScreen(")

        assertTrue(seriesPlayback.contains("OnDemandPlaybackSurface("))
        assertFalse(seriesPlayback.contains("showNativeController = true"))
        assertTrue(offlinePlayback.contains("OnDemandPlaybackSurface("))
        assertFalse(offlinePlayback.contains("useController = true"))
        assertFalse(offlinePlayback.contains("LibraryOfflineBadge"))
        assertFalse(offlinePlayback.contains("OFFLINE"))
        assertFalse(offlinePlayback.contains("Local file"))
    }

    @Test
    fun `movie remains the canonical custom-control baseline`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val moviePlayback = sourceBlockAfter(vod, "private fun VodPlaybackScreen(")

        assertTrue(moviePlayback.contains("useController = false"))
        assertTrue(moviePlayback.contains("VOD_CONTROLS_AUTO_HIDE_MILLIS"))
        assertTrue(moviePlayback.contains("Slider("))
        assertFalse(moviePlayback.contains("PlaybackOriginBadge"))
    }
}
