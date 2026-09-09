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
        assertTrue(shared.contains("PlayerFullscreenSystemBarsEffect(enabled = true)"))
        assertFalse(shared.contains("ONLINE"))
        assertFalse(shared.contains("OFFLINE"))
        assertFalse(shared.contains("Local file"))
    }

    @Test
    fun `live and on-demand share one fullscreen visual language`() {
        val live = sourceText("src/main/java/app/ownplay/player/ui/PlaybackScreen.kt")
        val onDemand = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")
        val systemBars = sourceText("src/main/java/app/ownplay/player/ui/PlayerFullscreenSystemBarsEffect.kt")

        listOf(live, onDemand).forEach { player ->
            assertTrue(player.contains("RESIZE_MODE_FIT"))
            assertTrue(player.contains("RoundedCornerShape(12.dp)"))
            assertTrue(player.contains("Color.Black.copy(alpha = 0.82f)"))
            assertTrue(player.contains("AnimatedVisibility("))
            assertTrue(player.contains("fadeIn()"))
            assertTrue(player.contains("fadeOut()"))
        }

        assertTrue(live.contains("FullscreenSystemBarsEffect(enabled = true)"))
        assertTrue(onDemand.contains("PlayerFullscreenSystemBarsEffect(enabled = true)"))
        assertTrue(systemBars.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"))
        assertTrue(systemBars.contains("hide(WindowInsetsCompat.Type.systemBars())"))
        assertTrue(systemBars.contains("show(WindowInsetsCompat.Type.systemBars())"))
    }

    @Test
    fun `playback origin metadata never renders persistent fullscreen chrome`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextOwnPlayApp.kt")
        val activity = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val onDemand = sourceText("src/main/java/app/ownplay/player/ui/OnDemandPlaybackSurface.kt")
        val live = sourceText("src/main/java/app/ownplay/player/ui/PlaybackScreen.kt")

        listOf(shell, activity, onDemand, live).forEach { source ->
            assertFalse(source.contains("PlaybackOriginBadge"))
            assertFalse(source.contains("\"ONLINE\""))
            assertFalse(source.contains("\"OFFLINE\""))
        }
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
