package app.ownplay.player.ui

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandPlaybackPresentationContractTest {
    @Test
    fun `shared on-demand surface owns transient custom controls without origin chrome`() {
        val shared = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")

        assertTrue(shared.contains("showNativeController = false"))
        assertTrue(shared.contains("ON_DEMAND_CONTROLS_AUTO_HIDE_MILLIS"))
        assertTrue(shared.contains("AnimatedVisibility("))
        assertTrue(shared.contains("fadeIn()"))
        assertTrue(shared.contains("fadeOut()"))
        assertTrue(shared.contains("Slider("))
        assertTrue(shared.contains("scrubPositionMs"))
        assertTrue(shared.contains("scrubbing"))
        assertTrue(shared.contains("Icons.Filled.PlayArrow"))
        assertTrue(shared.contains("Icons.Filled.Pause"))
        assertTrue(shared.contains("navigationBarsPadding()"))
        assertTrue(shared.contains(".size(34.dp)"))
        assertFalse(shared.contains("ONLINE"))
        assertFalse(shared.contains("OFFLINE"))
        assertFalse(shared.contains("Local file"))
    }

    @Test
    fun `live and on-demand share one fullscreen visual language`() {
        val live = sourceText("src/main/java/app/ownplay/player/ui/PlaybackScreen.kt")
        val onDemand = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")

        listOf(live, onDemand).forEach { player ->
            assertTrue(player.contains("RESIZE_MODE_FIT"))
            assertTrue(player.contains("RoundedCornerShape(12.dp)"))
            assertTrue(player.contains("Color.Black.copy(alpha = 0.82f)"))
            assertTrue(player.contains("AnimatedVisibility("))
            assertTrue(player.contains("fadeIn()"))
            assertTrue(player.contains("fadeOut()"))
        }
    }

    @Test
    fun `playback origin metadata never renders persistent fullscreen chrome`() {
        val badge = sourceText("src/main/java/app/ownplay/player/ui/PlaybackOriginBadge.kt")
        val shell = sourceText("src/main/java/app/ownplay/player/ui/OwnPlayApp.kt")

        assertTrue(badge.contains("Playback origin is runtime metadata"))
        assertTrue(badge.contains(") = Unit"))
        assertFalse(badge.contains("Text("))
        assertFalse(badge.contains("Icon("))
        assertFalse(badge.contains("Surface("))
        assertFalse(badge.contains("ONLINE"))
        assertFalse(badge.contains("OFFLINE"))
        assertFalse(shell.contains("\"ONLINE\""))
        assertFalse(shell.contains("\"OFFLINE\""))
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
