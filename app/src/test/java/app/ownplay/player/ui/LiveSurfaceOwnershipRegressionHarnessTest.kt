package app.ownplay.player.ui

import app.ownplay.player.BuildConfig
import app.ownplay.player.liveRotationFullscreenEnabled
import app.ownplay.player.playback.LiveActivityBackgroundAction
import app.ownplay.player.playback.LiveActivityLifecyclePolicy
import app.ownplay.player.playback.LivePlaybackPresentationSurface
import app.ownplay.player.playback.LivePlaybackSurfaceTeardown
import app.ownplay.player.playback.LivePlaybackTransitionGate
import app.ownplay.player.playback.LivePlaybackTransitionTarget
import app.ownplay.player.playback.PlaybackMediaKind
import app.ownplay.player.playback.PlaybackRequest
import app.ownplay.player.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSurfaceOwnershipRegressionHarnessTest {
    @Test
    fun `preview fullscreen pip lifecycle preview close and source switch keep one live surface`() {
        val harness = LiveSurfaceOwnershipHarness()

        harness.openPreview(sourceId = "source-a", channelId = "channel-1")
        harness.assertOnly(SurfaceId.PREVIEW)

        harness.openFullscreen()
        harness.assertOnly(SurfaceId.FULLSCREEN)

        harness.enterPictureInPicture()
        harness.assertOnly(SurfaceId.PIP)
        assertEquals(SurfaceId.FULLSCREEN, harness.pictureInPictureReturnTarget)

        harness.returnFromPictureInPicture()
        harness.assertOnly(SurfaceId.FULLSCREEN)
        assertNull(harness.pictureInPictureReturnTarget)

        harness.background()
        harness.assertNone()

        harness.foreground()
        harness.assertOnly(SurfaceId.FULLSCREEN)

        harness.returnToPreview()
        harness.assertOnly(SurfaceId.PREVIEW)

        harness.closePreview()
        harness.assertNone()

        harness.openPreview(sourceId = "source-a", channelId = "channel-2")
        harness.assertOnly(SurfaceId.PREVIEW)

        harness.switchSource("source-b")
        harness.assertNone()
        assertNull(harness.pictureInPictureReturnTarget)

        harness.openPreview(sourceId = "source-b", channelId = "channel-9")
        harness.assertOnly(SurfaceId.PREVIEW)

        assertTrue(
            "Live playback must never have more than one bound video surface",
            harness.maximumBoundSurfaceCount <= 1,
        )
    }

    @Test
    fun `preview pip rotations keep pip ownership until return to preview`() {
        val harness = LiveSurfaceOwnershipHarness()

        harness.openPreview(sourceId = "source-a", channelId = "channel-1")
        harness.enterPictureInPicture()
        harness.assertOnly(SurfaceId.PIP)
        assertEquals(SurfaceId.PREVIEW, harness.pictureInPictureReturnTarget)

        harness.rotateLandscape()
        harness.assertOnly(SurfaceId.PIP)
        assertEquals(SurfaceId.PREVIEW, harness.pictureInPictureReturnTarget)

        harness.rotatePortrait()
        harness.assertOnly(SurfaceId.PIP)
        assertEquals(SurfaceId.PREVIEW, harness.pictureInPictureReturnTarget)

        harness.returnFromPictureInPicture()
        harness.assertOnly(SurfaceId.PREVIEW)
        assertNull(harness.pictureInPictureReturnTarget)
        assertTrue(
            "PiP rotation must never create a competing Live surface",
            harness.maximumBoundSurfaceCount <= 1,
        )
    }

    @Test
    fun `stale pip return target is discarded instead of rebound`() {
        val harness = LiveSurfaceOwnershipHarness()

        harness.openPreview(sourceId = "source-a", channelId = "channel-1")
        harness.openFullscreen()
        harness.enterPictureInPicture()
        harness.invalidatePictureInPictureReturnTarget()

        harness.returnFromPictureInPicture()

        harness.assertNone()
        assertNull(harness.pictureInPictureReturnTarget)
        assertTrue(
            "A stale return target must not create a second or resurrected surface",
            harness.maximumBoundSurfaceCount <= 1,
        )
    }

    @Test
    fun `movie and series keep media3 pip transfer with target specific lifecycle behavior`() {
        val expectedBackgroundAction = if (BuildConfig.IS_TV_BUILD) {
            LiveActivityBackgroundAction.NONE
        } else {
            LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE
        }

        listOf(
            PlaybackMediaKind.MOVIE,
            PlaybackMediaKind.SERIES_EPISODE,
        ).forEach { mediaKind ->
            assertEquals(
                PictureInPictureSurfaceBindingMode.MEDIA3_TRANSFER,
                PictureInPictureSurfaceHandoffPolicy.modeFor(mediaKind),
            )

            val events = mutableListOf<String>()
            PictureInPictureSurfaceHandoffPolicy.handoff(
                mode = PictureInPictureSurfaceHandoffPolicy.modeFor(mediaKind),
                detachCurrentSurface = { events += "detach" },
                bindDestinationSurface = { events += "bind" },
            )
            assertEquals(listOf("bind"), events)

            assertEquals(
                expectedBackgroundAction,
                LiveActivityLifecyclePolicy.backgroundAction(
                    state = PlaybackState.Playing(request(mediaKind, "source", "content")),
                    inPictureInPicture = false,
                    changingConfigurations = false,
                ),
            )
        }
    }
}

private enum class SurfaceId {
    PREVIEW,
    FULLSCREEN,
    PIP,
}

private class LiveSurfaceOwnershipHarness {
    private val gate = LivePlaybackTransitionGate()
    private val boundSurfaces = linkedSetOf<SurfaceId>()
    private val attachedSurfaces = mutableSetOf<SurfaceId>()

    private var presentation: LivePlaybackPresentationSurface? = null
    private var sourceId: String? = null
    private var channelId: String? = null
    private var lifecycleRetainedSurface: SurfaceId? = null
    private var inPictureInPicture: Boolean = false

    var pictureInPictureReturnTarget: SurfaceId? = null
        private set

    var maximumBoundSurfaceCount: Int = 0
        private set

    fun openPreview(sourceId: String, channelId: String) {
        this.sourceId = sourceId
        this.channelId = channelId
        presentation = LivePlaybackPresentationSurface.PREVIEW
        attachAndBind(SurfaceId.PREVIEW)
        gate.reconcileObserved(currentTransitionTarget())
    }

    fun openFullscreen() {
        val target = requireTarget(LivePlaybackPresentationSurface.FULLSCREEN)
        gate.requestHandoff(
            target = target,
            detachCurrentSurface = ::detachCurrent,
            stopPlayback = {},
            switchPresentation = {
                presentation = LivePlaybackPresentationSurface.FULLSCREEN
                attachedSurfaces.remove(SurfaceId.PREVIEW)
            },
            startPlayback = {},
        )
        attachAndBind(SurfaceId.FULLSCREEN)
        gate.reconcileObserved(target)
    }

    fun enterPictureInPicture() {
        val currentSurface = boundSurfaces.singleOrNull()
        pictureInPictureReturnTarget = currentSurface
            ?.takeIf { it != SurfaceId.PIP && it in attachedSurfaces }
        inPictureInPicture = true

        PictureInPictureSurfaceHandoffPolicy.handoff(
            mode = PictureInPictureSurfaceHandoffPolicy.modeFor(PlaybackMediaKind.LIVE),
            detachCurrentSurface = { detachCurrent() },
            bindDestinationSurface = { attachAndBind(SurfaceId.PIP) },
        )
    }

    fun rotateLandscape() {
        if (
            liveRotationFullscreenEnabled(
                isSmartphone = true,
                inPictureInPicture = inPictureInPicture,
            ) && presentation == LivePlaybackPresentationSurface.PREVIEW
        ) {
            openFullscreen()
        }
    }

    fun rotatePortrait() {
        if (
            liveRotationFullscreenEnabled(
                isSmartphone = true,
                inPictureInPicture = inPictureInPicture,
            ) && presentation == LivePlaybackPresentationSurface.FULLSCREEN
        ) {
            returnToPreview()
        }
    }

    fun returnFromPictureInPicture() {
        val target = pictureInPictureReturnTarget
            ?.takeIf { it in attachedSurfaces }
        val mode = PictureInPictureSurfaceHandoffPolicy.modeFor(PlaybackMediaKind.LIVE)

        if (target != null) {
            PictureInPictureSurfaceHandoffPolicy.handoff(
                mode = mode,
                detachCurrentSurface = { detachCurrent() },
                bindDestinationSurface = { bind(target) },
            )
        } else if (mode == PictureInPictureSurfaceBindingMode.DETACH_BEFORE_BIND) {
            detachCurrent()
        }

        attachedSurfaces.remove(SurfaceId.PIP)
        pictureInPictureReturnTarget = null
        inPictureInPicture = false
    }

    fun invalidatePictureInPictureReturnTarget() {
        pictureInPictureReturnTarget?.let(attachedSurfaces::remove)
    }

    fun background() {
        val action = LiveActivityLifecyclePolicy.backgroundAction(
            state = PlaybackState.Playing(
                request(
                    mediaKind = PlaybackMediaKind.LIVE,
                    sourceId = requireNotNull(sourceId),
                    channelId = requireNotNull(channelId),
                ),
            ),
            inPictureInPicture = false,
            changingConfigurations = false,
        )
        assertEquals(LiveActivityBackgroundAction.SUSPEND_AND_RETAIN_SURFACE, action)

        lifecycleRetainedSurface = boundSurfaces.singleOrNull()
        detachCurrent()
    }

    fun foreground() {
        val retained = lifecycleRetainedSurface
        lifecycleRetainedSurface = null
        if (retained != null && retained in attachedSurfaces) {
            bind(retained)
        }
    }

    fun returnToPreview() {
        val target = requireTarget(LivePlaybackPresentationSurface.PREVIEW)
        gate.requestHandoff(
            target = target,
            detachCurrentSurface = ::detachCurrent,
            stopPlayback = {},
            switchPresentation = {
                presentation = LivePlaybackPresentationSurface.PREVIEW
                attachedSurfaces.remove(SurfaceId.FULLSCREEN)
            },
            startPlayback = {},
        )
        attachAndBind(SurfaceId.PREVIEW)
        gate.reconcileObserved(target)
    }

    fun closePreview() {
        LivePlaybackSurfaceTeardown.stopAfterDetaching(
            detachCurrentSurface = ::detachCurrent,
            stopPlayback = {},
            clearPresentation = {
                attachedSurfaces.remove(SurfaceId.PREVIEW)
                presentation = null
                sourceId = null
                channelId = null
                pictureInPictureReturnTarget = null
                inPictureInPicture = false
            },
        )
        gate.reconcileObserved(null)
    }

    fun switchSource(newSourceId: String) {
        if (newSourceId != sourceId && presentation != null) {
            LivePlaybackSurfaceTeardown.stopAfterDetaching(
                detachCurrentSurface = ::detachCurrent,
                stopPlayback = {},
                clearPresentation = {
                    attachedSurfaces.clear()
                    presentation = null
                    channelId = null
                    pictureInPictureReturnTarget = null
                    inPictureInPicture = false
                },
            )
            gate.reconcileObserved(null)
        }
        sourceId = newSourceId
    }

    fun assertOnly(surface: SurfaceId) {
        assertEquals(setOf(surface), boundSurfaces)
    }

    fun assertNone() {
        assertTrue(boundSurfaces.isEmpty())
    }

    private fun attachAndBind(surface: SurfaceId) {
        attachedSurfaces += surface
        bind(surface)
    }

    private fun bind(surface: SurfaceId) {
        boundSurfaces += surface
        maximumBoundSurfaceCount = maxOf(maximumBoundSurfaceCount, boundSurfaces.size)
    }

    private fun detachCurrent(): Boolean {
        val current = boundSurfaces.singleOrNull() ?: return false
        boundSurfaces.remove(current)
        return true
    }

    private fun requireTarget(
        surface: LivePlaybackPresentationSurface,
    ): LivePlaybackTransitionTarget = LivePlaybackTransitionTarget(
        surface = surface,
        sourceId = requireNotNull(sourceId),
        channelId = requireNotNull(channelId),
    )

    private fun currentTransitionTarget(): LivePlaybackTransitionTarget? {
        val currentPresentation = presentation ?: return null
        return requireTarget(currentPresentation)
    }
}

private fun request(
    mediaKind: PlaybackMediaKind,
    sourceId: String,
    channelId: String,
) = PlaybackRequest(
    sourceId = sourceId,
    channelId = channelId,
    mediaKind = mediaKind,
    providerStreamId = if (mediaKind == PlaybackMediaKind.SERIES_EPISODE) 7 else null,
)
