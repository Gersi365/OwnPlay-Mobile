package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureWiringContractTest {
    @Test
    fun `MainActivity routes the active playback destination into PiP`() {
        val source = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/MainActivity.kt"),
        )

        assertTrue(
            source.contains(
                "val isInPictureInPictureMode by playbackWindowController.isInPictureInPictureMode.collectAsState()",
            ),
        )
        assertTrue(source.contains("isInPictureInPictureMode -> { PictureInPicturePlaybackSurface("))
        assertTrue(source.contains("videoOutput = runtime.playbackVideoOutput"))
        assertTrue(source.contains("mediaKind = currentPlaybackMediaKind()"))
        assertTrue(source.contains("liveWasFullscreen = playbackFullscreen"))
        assertTrue(
            source.contains(
                "onPlaybackSurfaceActiveChanged = playbackWindowController::updatePlaybackSurfaceState",
            ),
        )
    }

    @Test
    fun `MainActivity does not apply normal background suspension while PiP owns playback`() {
        val source = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val onStop = normalizedSource(sourceBlockAfter(source, "override fun onStop()"))
        val onResume = normalizedSource(sourceBlockAfter(source, "override fun onResume()"))
        val onPipChanged = normalizedSource(
            sourceBlockAfter(
                source,
                "override fun onPictureInPictureModeChanged(",
            ),
        )

        assertTrue(onStop.contains("inPictureInPicture = isInPictureInPictureMode"))
        assertTrue(
            onStop.contains(
                "PlaybackInteractionBridge.suspendCurrentForLifecycle(runtime.playbackVideoOutput)",
            ),
        )
        assertTrue(onStop.contains("runtime.playbackController.suspendForBackground()"))
        assertTrue(
            onResume.contains(
                "PlaybackInteractionBridge.resumeLifecycleSuspended(runtime.playbackVideoOutput)",
            ),
        )
        assertTrue(onResume.contains("runtime.playbackController.resumeAfterBackground()"))
        assertTrue(
            onPipChanged.contains(
                "playbackWindowController.onPictureInPictureModeChanged(isInPictureInPictureMode)",
            ),
        )
    }

    @Test
    fun `PiP surface captures and validates one return destination`() {
        val source = normalizedSource(
            sourceText("src/main/java/app/ownplay/player/ui/PictureInPicturePlaybackSurface.kt"),
        )

        assertTrue(source.contains("PlaybackInteractionBridge.currentBoundView()"))
        assertTrue(source.contains("WeakReference(candidate)"))
        assertTrue(
            Regex("PictureInPictureSurfaceHandoffPolicy\\.handoff\\(")
                .findAll(source)
                .count() >= 2,
        )
        assertTrue(source.contains("PlaybackInteractionBridge.currentBoundView() === view"))
        assertTrue(source.contains("candidate.isAttachedToWindow"))
        assertTrue(source.contains("bindDestinationSurface = { videoOutput.bind(this) }"))
        assertTrue(source.contains("bindDestinationSurface = { videoOutput.bind(target) }"))
        assertTrue(source.contains("view.useController = false"))
    }

    @Test
    fun `Media3 video output keeps bridge ownership synchronized across target switches`() {
        val source = sourceText(
            "src/main/java/app/ownplay/player/playback/Media3PlaybackEngine.kt",
        )
        val bind = normalizedSource(sourceBlockAfter(source, "override fun bind(view: PlayerView)"))
        val unbind = normalizedSource(sourceBlockAfter(source, "override fun unbind(view: PlayerView)"))

        assertTrue(bind.contains("PlayerView.switchTargetView(player, previousView, view)"))
        assertTrue(bind.contains("PlaybackInteractionBridge.observeBoundView(view)"))
        assertTrue(unbind.contains("PlaybackInteractionBridge.observeUnboundView(view)"))
        assertTrue(unbind.contains("view.player = null"))
    }

    @Test
    fun `Mobile shell exposes every active playback presentation to PiP eligibility`() {
        val source = normalizedSource(
            sourceText("src/mobile/java/app/ownplay/player/ui/MobileVNextOwnPlayApp.kt"),
        )

        assertTrue(
            source.contains(
                "val previewActive = section == MobileShellDestination.LIVE && activeSelection != null && fullscreenSelection == null",
            ),
        )
        assertTrue(
            source.contains(
                "val playbackSurfaceActive = previewActive || fullscreenSelection != null || vodFullscreen || seriesFullscreen || libraryFullscreen",
            ),
        )
        assertTrue(
            source.contains(
                "LaunchedEffect(playbackSurfaceActive) { onPlaybackSurfaceActiveChanged(playbackSurfaceActive) }",
            ),
        )
    }
}
