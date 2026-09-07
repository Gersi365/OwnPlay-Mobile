package app.ownplay.player.ui

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackRotationAndEpgContractTest {
    @Test
    fun `mobile activity handles orientation changes without recreation`() {
        val manifest = sourceText("src/mobile/AndroidManifest.xml")

        assertTrue(
            manifest.contains(
                "android:configChanges=\"orientation|screenSize|smallestScreenSize|screenLayout\"",
            ),
        )
    }

    @Test
    fun `live rotation handoff preserves the current stream and ignores pip`() {
        val activity = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val rotation = sourceBlockAfter(activity, "private fun handleLivePlaybackRotation(")

        assertTrue(rotation.contains("if (!::runtime.isInitialized || isInPictureInPictureMode) return"))
        assertTrue(rotation.contains("LivePlaybackSurfaceHandoff.transferAcrossPresentation("))
        assertTrue(rotation.contains("entryReason = LiveFullscreenEntryReason.ROTATION"))
        assertTrue(rotation.contains("presentationSession.showPreview(selection)"))
        assertFalse(rotation.contains("runtime.playbackController.start("))
        assertFalse(rotation.contains("runtime.playbackController.stop()"))
    }

    @Test
    fun `preview and fullscreen video use sensor while pip follows the system`() {
        val controller = sourceText("src/main/java/app/ownplay/player/ui/PlaybackWindowController.kt")

        assertTrue(
            controller.contains(
                "inPictureInPicture -> PlaybackOrientationIntent.FOLLOW_SYSTEM",
            ),
        )
        assertTrue(
            controller.contains(
                "fullscreen || livePreviewActive -> PlaybackOrientationIntent.SENSOR",
            ),
        )
        assertTrue(controller.contains("else -> PlaybackOrientationIntent.PORTRAIT"))
    }

    @Test
    fun `fullscreen chrome is reasserted after pip without overriding pip window ownership`() {
        val controller = sourceText("src/main/java/app/ownplay/player/ui/PlaybackWindowController.kt")
        val fullscreen = sourceBlockAfter(controller, "fun updateFullscreenState(")
        val pip = sourceBlockAfter(controller, "fun onPictureInPictureModeChanged(")
        val refresh = sourceBlockAfter(controller, "fun refreshWindowState()")
        val pipOwnership = sourceBlockAfter(controller, "private fun isPictureInPictureOwned()")
        val orientation = sourceBlockAfter(controller, "private fun applyOrientationPolicy()")
        val systemBars = sourceBlockAfter(controller, "private fun applySystemBarPolicy()")

        assertTrue(fullscreen.contains("applySystemBarPolicy()"))
        assertTrue(fullscreen.contains("scheduleSystemBarPolicyRefresh()"))
        assertTrue(pip.contains("applySystemBarPolicy()"))
        assertTrue(pip.contains("if (!isInPictureInPictureMode)"))
        assertTrue(pip.contains("scheduleSystemBarPolicyRefresh()"))
        assertTrue(refresh.contains("applySystemBarPolicy()"))
        assertTrue(pipOwnership.contains("_isInPictureInPictureMode.value"))
        assertTrue(pipOwnership.contains("activity.isInPictureInPictureMode"))
        assertTrue(orientation.contains("inPictureInPicture = isPictureInPictureOwned()"))
        assertTrue(
            systemBars.contains(
                "if (isPictureInPictureOwned() || activity.isFinishing) return",
            ),
        )
        assertTrue(systemBars.contains("hide(WindowInsetsCompat.Type.systemBars())"))
        assertTrue(systemBars.contains("hide(WindowInsetsCompat.Type.statusBars())"))
        assertTrue(systemBars.contains("show(WindowInsetsCompat.Type.navigationBars())"))
    }

    @Test
    fun `movie series and offline playback stay on the shared fullscreen rotation contract`() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")
        val moviePlayback = sourceBlockAfter(vod, "private fun VodPlaybackScreen(")
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")
        val seriesPlayback = sourceBlockAfter(series, "private fun SeriesPlaybackScreen(")
        val offline = sourceText("src/main/java/app/ownplay/player/ui/library/LibraryPlaybackScreen.kt")
        val offlinePlayback = sourceBlockAfter(offline, "internal fun LibraryPlaybackScreen(")

        listOf(moviePlayback, seriesPlayback, offlinePlayback).forEach { playback ->
            assertTrue(playback.contains("onFullscreenStateChanged(true)"))
            assertTrue(playback.contains("OnDemandPlaybackSurface("))
        }
    }

    @Test
    fun `visible live EPG prefetch is bounded and concurrent`() {
        val live = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")

        assertTrue(live.contains("MOBILE_EPG_PREFETCH_CONCURRENCY = 4"))
        assertTrue(live.contains("missingChannels.chunked(MOBILE_EPG_PREFETCH_CONCURRENCY)"))
        assertTrue(live.contains("batch.map { channel ->"))
        assertTrue(live.contains(".awaitAll()"))
        assertFalse(live.contains("for (index in startIndex until endExclusive)"))
    }
}
