package app.ownplay.player

import app.ownplay.player.testing.normalizedSource
import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackContinuityContractTest {
    @Test
    fun `same previewed channel activation opens fullscreen before new playback`() {
        val live = sourceText("src/mobile/java/app/ownplay/player/ui/TargetLiveRoute.kt")
        val selectChannel = normalizedSource(
            sourceBlockAfter(
                live,
                "fun selectChannel(channelId: String)",
            ),
        )

        val sameChannelCheck = "currentPreview?.request?.channelId == channelId"
        val fullscreenCall = "onOpenFullscreen(currentPreview)"
        val newPlaybackCall = "onPreviewRequested(action.selection)"

        assertTrue(selectChannel.contains(sameChannelCheck))
        assertTrue(selectChannel.contains(fullscreenCall))
        assertTrue(selectChannel.contains("return"))
        assertTrue(selectChannel.contains(newPlaybackCall))
        assertTrue(selectChannel.indexOf(fullscreenCall) < selectChannel.indexOf(newPlaybackCall))
    }

    @Test
    fun `Mobile Preview to fullscreen uses the continuity transition gate`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val handoff = normalizedSource(
            sourceBlockAfter(
                shell,
                "fun openLiveFullscreen(",
            ),
        )

        assertTrue(handoff.contains("liveTransitionGate.requestHandoff("))
        assertTrue(handoff.contains("target = LivePlaybackTransitionTarget.fullscreen(selection)"))
        assertTrue(
            handoff.contains(
                "PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)",
            ),
        )
        assertTrue(handoff.contains("stopPlayback = runtime.playbackController::stop"))
        assertTrue(handoff.contains("runtime.livePlaybackPresentationSession.showFullscreen("))
        assertTrue(handoff.contains("startPlayback = { runtime.playbackController.start(selection.request) }"))
    }

    @Test
    fun `Mobile fullscreen to Preview uses the continuity transition gate`() {
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val handoff = normalizedSource(
            sourceBlockAfter(
                shell,
                "fun returnLiveToPreview(",
            ),
        )

        assertTrue(handoff.contains("liveTransitionGate.requestHandoff("))
        assertTrue(handoff.contains("target = LivePlaybackTransitionTarget.preview(selection)"))
        assertTrue(
            handoff.contains(
                "PlaybackInteractionBridge.detachCurrent(runtime.playbackVideoOutput)",
            ),
        )
        assertTrue(handoff.contains("stopPlayback = runtime.playbackController::stop"))
        assertTrue(handoff.contains("runtime.livePlaybackPresentationSession.showPreview(selection)"))
        assertTrue(handoff.contains("startPlayback = { runtime.playbackController.start(selection.request) }"))
    }

    @Test
    fun `same channel gate path transfers surface without stop or start`() {
        val transition = sourceText(
            "src/main/java/app/ownplay/player/playback/LivePlaybackPresentation.kt",
        )
        val requestHandoff = normalizedSource(
            sourceBlockAfter(
                transition,
                "fun requestHandoff(",
            ),
        )

        assertTrue(requestHandoff.contains("fromTarget?.isSameContentAs(target) == true"))
        assertTrue(requestHandoff.contains("LivePlaybackSurfaceHandoff.transferAcrossPresentation("))
        assertTrue(requestHandoff.contains("LivePlaybackSurfaceHandoff.restartAcrossPresentation("))

        val transfer = normalizedSource(
            sourceBlockAfter(
                transition,
                "fun transferAcrossPresentation(",
            ),
        )
        assertTrue(transfer.contains("detachCurrentSurface()"))
        assertTrue(transfer.contains("switchPresentation()"))
        assertFalse(transfer.contains("stopPlayback()"))
        assertFalse(transfer.contains("startPlayback()"))
    }

    @Test
    fun `different Live content keeps explicit restart path`() {
        val transition = sourceText(
            "src/main/java/app/ownplay/player/playback/LivePlaybackPresentation.kt",
        )
        val restart = normalizedSource(
            sourceBlockAfter(
                transition,
                "fun restartAcrossPresentation(",
            ),
        )

        assertTrue(restart.contains("detachCurrentSurface()"))
        assertTrue(restart.contains("stopPlayback()"))
        assertTrue(restart.contains("switchPresentation()"))
        assertTrue(restart.contains("startPlayback()"))
    }
}
