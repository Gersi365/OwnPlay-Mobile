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
        assertTrue(shared.contains("scrubPositionMs"))
        assertTrue(shared.contains("scrubbing"))
        assertTrue(shared.contains("Icons.Filled.PlayArrow"))
        assertTrue(shared.contains("Icons.Filled.Pause"))
        assertFalse(shared.contains("ONLINE"))
        assertFalse(shared.contains("OFFLINE"))
        assertFalse(shared.contains("Local file"))
    }

    @Test
    fun `movie series and offline use one shared fullscreen presentation`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val moviePlayback = sourceBlockAfter(vod, "private fun VodPlaybackScreen(")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
        val seriesPlayback = sourceBlockAfter(series, "private fun SeriesPlaybackScreen(")
        val offline = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")
        val offlinePlayback = sourceBlockAfter(offline, "internal fun LibraryPlaybackScreen(")

        listOf(moviePlayback, seriesPlayback, offlinePlayback).forEach { playback ->
            assertTrue(playback.contains("OnDemandPlaybackSurface("))
            assertFalse(playback.contains("useController = true"))
            assertFalse(playback.contains("PlaybackOriginBadge"))
            assertFalse(playback.contains("OFFLINE"))
            assertFalse(playback.contains("Local file"))
        }
        assertFalse(moviePlayback.contains("Slider("))
        assertFalse(seriesPlayback.contains("Slider("))
        assertFalse(offlinePlayback.contains("Slider("))
    }
}
