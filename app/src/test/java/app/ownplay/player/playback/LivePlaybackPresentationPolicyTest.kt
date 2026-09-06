package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackPresentationPolicyTest {
    @Test
    fun `rotation enters fullscreen only from an active preview`() {
        assertTrue(
            LivePlaybackPresentationPolicy.shouldEnterFullscreenFromRotation(
                rotationFullscreenEnabled = true,
                isLandscape = true,
                hasSelection = true,
                alreadyFullscreen = false,
            ),
        )
        assertFalse(
            LivePlaybackPresentationPolicy.shouldEnterFullscreenFromRotation(
                rotationFullscreenEnabled = false,
                isLandscape = true,
                hasSelection = true,
                alreadyFullscreen = false,
            ),
        )
        assertFalse(
            LivePlaybackPresentationPolicy.shouldEnterFullscreenFromRotation(
                rotationFullscreenEnabled = true,
                isLandscape = false,
                hasSelection = true,
                alreadyFullscreen = false,
            ),
        )
        assertFalse(
            LivePlaybackPresentationPolicy.shouldEnterFullscreenFromRotation(
                rotationFullscreenEnabled = true,
                isLandscape = true,
                hasSelection = false,
                alreadyFullscreen = false,
            ),
        )
    }

    @Test
    fun `portrait returns only rotation-entered fullscreen to preview`() {
        assertTrue(
            LivePlaybackPresentationPolicy.shouldReturnToPreviewFromRotation(
                rotationFullscreenEnabled = true,
                isPortrait = true,
                entryReason = LiveFullscreenEntryReason.ROTATION,
                isFullscreen = true,
            ),
        )
        assertFalse(
            LivePlaybackPresentationPolicy.shouldReturnToPreviewFromRotation(
                rotationFullscreenEnabled = true,
                isPortrait = true,
                entryReason = LiveFullscreenEntryReason.USER,
                isFullscreen = true,
            ),
        )
        assertFalse(
            LivePlaybackPresentationPolicy.shouldReturnToPreviewFromRotation(
                rotationFullscreenEnabled = true,
                isPortrait = false,
                entryReason = LiveFullscreenEntryReason.ROTATION,
                isFullscreen = true,
            ),
        )
    }

    @Test
    fun `same stream surface transfer detaches before presentation switch`() {
        val events = mutableListOf<String>()

        val detached = LivePlaybackSurfaceHandoff.transferAcrossPresentation(
            detachCurrentSurface = {
                events += "detach"
                true
            },
            switchPresentation = { events += "switch" },
        )

        assertTrue(detached)
        assertEquals(listOf("detach", "switch"), events)
    }

    @Test
    fun `same stream transfer still switches when no surface is currently bound`() {
        val events = mutableListOf<String>()

        val detached = LivePlaybackSurfaceHandoff.transferAcrossPresentation(
            detachCurrentSurface = {
                events += "detach"
                false
            },
            switchPresentation = { events += "switch" },
        )

        assertFalse(detached)
        assertEquals(listOf("detach", "switch"), events)
    }

    @Test
    fun `content replacement restart detaches before stop switch and start`() {
        val events = mutableListOf<String>()

        val detached = LivePlaybackSurfaceHandoff.restartAcrossPresentation(
            detachCurrentSurface = {
                events += "detach"
                true
            },
            stopPlayback = { events += "stop" },
            switchPresentation = { events += "switch" },
            startPlayback = { events += "start" },
        )

        assertTrue(detached)
        assertEquals(listOf("detach", "stop", "switch", "start"), events)
    }

    @Test
    fun `live teardown detaches before stop and presentation clear`() {
        val events = mutableListOf<String>()

        val detached = LivePlaybackSurfaceTeardown.stopAfterDetaching(
            detachCurrentSurface = {
                events += "detach"
                true
            },
            stopPlayback = { events += "stop" },
            clearPresentation = { events += "clear" },
        )

        assertTrue(detached)
        assertEquals(listOf("detach", "stop", "clear"), events)
    }

    @Test
    fun `live teardown still stops and clears when no surface is bound`() {
        val events = mutableListOf<String>()

        val detached = LivePlaybackSurfaceTeardown.stopAfterDetaching(
            detachCurrentSurface = {
                events += "detach"
                false
            },
            stopPlayback = { events += "stop" },
            clearPresentation = { events += "clear" },
        )

        assertFalse(detached)
        assertEquals(listOf("detach", "stop", "clear"), events)
    }

    @Test
    fun `transition gate preserves stream for same channel presentation change`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel",
        )
        val fullscreen = preview.copy(surface = LivePlaybackPresentationSurface.FULLSCREEN)
        val events = mutableListOf<String>()
        gate.reconcileObserved(preview)

        val result = gate.requestHandoff(
            target = fullscreen,
            detachCurrentSurface = { events += "detach"; true },
            stopPlayback = { events += "stop" },
            switchPresentation = { events += "switch" },
            startPlayback = { events += "start" },
        )

        assertEquals(LivePlaybackTransitionDecision.APPLIED, result)
        assertEquals(listOf("detach", "switch"), events)
    }

    @Test
    fun `transition gate restarts when live content changes`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel-a",
        )
        val differentContent = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.FULLSCREEN,
            sourceId = "source",
            channelId = "channel-b",
        )
        val events = mutableListOf<String>()
        gate.reconcileObserved(preview)

        val result = gate.requestHandoff(
            target = differentContent,
            detachCurrentSurface = { events += "detach"; true },
            stopPlayback = { events += "stop" },
            switchPresentation = { events += "switch" },
            startPlayback = { events += "start" },
        )

        assertEquals(LivePlaybackTransitionDecision.APPLIED, result)
        assertEquals(listOf("detach", "stop", "switch", "start"), events)
    }

    @Test
    fun `transition gate ignores repeated pending request`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel",
        )
        val target = preview.copy(surface = LivePlaybackPresentationSurface.FULLSCREEN)
        val events = mutableListOf<String>()
        gate.reconcileObserved(preview)

        val first = gate.requestHandoff(
            target = target,
            detachCurrentSurface = { events += "detach"; true },
            stopPlayback = { events += "stop" },
            switchPresentation = { events += "switch" },
            startPlayback = { events += "start" },
        )
        val duplicate = gate.requestHandoff(
            target = target,
            detachCurrentSurface = { events += "duplicate-detach"; true },
            stopPlayback = { events += "duplicate-stop" },
            switchPresentation = { events += "duplicate-switch" },
            startPlayback = { events += "duplicate-start" },
        )

        assertEquals(LivePlaybackTransitionDecision.APPLIED, first)
        assertEquals(LivePlaybackTransitionDecision.DUPLICATE, duplicate)
        assertEquals(listOf("detach", "switch"), events)
    }

    @Test
    fun `transition gate accepts reverse destination while prior handoff is pending`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel",
        )
        val fullscreen = preview.copy(surface = LivePlaybackPresentationSurface.FULLSCREEN)
        val events = mutableListOf<String>()
        gate.reconcileObserved(preview)

        gate.requestHandoff(
            target = fullscreen,
            detachCurrentSurface = { events += "fullscreen-detach"; true },
            stopPlayback = { events += "fullscreen-stop" },
            switchPresentation = { events += "fullscreen-switch" },
            startPlayback = { events += "fullscreen-start" },
        )
        val reverse = gate.requestHandoff(
            target = preview,
            detachCurrentSurface = { events += "preview-detach"; false },
            stopPlayback = { events += "preview-stop" },
            switchPresentation = { events += "preview-switch" },
            startPlayback = { events += "preview-start" },
        )

        assertEquals(LivePlaybackTransitionDecision.APPLIED, reverse)
        assertEquals(
            listOf(
                "fullscreen-detach",
                "fullscreen-switch",
                "preview-detach",
                "preview-switch",
            ),
            events,
        )
    }

    @Test
    fun `compose acknowledgement makes same destination duplicate until state changes`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel",
        )
        gate.reconcileObserved(preview)

        val result = gate.requestHandoff(
            target = preview,
            detachCurrentSurface = { error("must not detach") },
            stopPlayback = { error("must not stop") },
            switchPresentation = { error("must not switch") },
            startPlayback = { error("must not start") },
        )

        assertEquals(LivePlaybackTransitionDecision.DUPLICATE, result)
    }

    @Test
    fun `failed handoff can be retried`() {
        val gate = LivePlaybackTransitionGate()
        val preview = LivePlaybackTransitionTarget(
            surface = LivePlaybackPresentationSurface.PREVIEW,
            sourceId = "source",
            channelId = "channel",
        )
        val target = preview.copy(surface = LivePlaybackPresentationSurface.FULLSCREEN)
        gate.reconcileObserved(preview)

        runCatching {
            gate.requestHandoff(
                target = target,
                detachCurrentSurface = { true },
                stopPlayback = {},
                switchPresentation = { error("boom") },
                startPlayback = {},
            )
        }

        val retry = gate.requestHandoff(
            target = target,
            detachCurrentSurface = { true },
            stopPlayback = {},
            switchPresentation = {},
            startPlayback = {},
        )

        assertEquals(LivePlaybackTransitionDecision.APPLIED, retry)
    }
}
