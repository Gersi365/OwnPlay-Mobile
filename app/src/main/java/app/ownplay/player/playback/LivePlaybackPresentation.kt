package app.ownplay.player.playback

/**
 * Describes why Live playback entered the dedicated fullscreen surface.
 *
 * USER keeps fullscreen active until the user explicitly exits it.
 * ROTATION lets a Smartphone return to Preview when the device rotates back to portrait.
 */
enum class LiveFullscreenEntryReason {
    USER,
    ROTATION,
}

/**
 * Single transition policy shared by portrait, landscape, and TV Live playback.
 * Device-specific input only decides whether rotation is allowed to request fullscreen;
 * the playback transition itself remains independent from device type.
 */
object LivePlaybackPresentationPolicy {
    fun shouldEnterFullscreenFromRotation(
        rotationFullscreenEnabled: Boolean,
        isLandscape: Boolean,
        hasSelection: Boolean,
        alreadyFullscreen: Boolean,
    ): Boolean =
        rotationFullscreenEnabled &&
            isLandscape &&
            hasSelection &&
            !alreadyFullscreen

    fun shouldReturnToPreviewFromRotation(
        rotationFullscreenEnabled: Boolean,
        isPortrait: Boolean,
        entryReason: LiveFullscreenEntryReason?,
        isFullscreen: Boolean,
    ): Boolean =
        rotationFullscreenEnabled &&
            isPortrait &&
            isFullscreen &&
            entryReason == LiveFullscreenEntryReason.ROTATION
}

/**
 * Executes Live handoffs between independent Preview and Fullscreen surfaces.
 *
 * The departing PlayerView is always detached before Compose switches presentation. That leaves
 * Media3 with no previous PlayerView target, so the new surface can bind directly to the same
 * ExoPlayer without invoking PlayerView.switchTargetView or restarting the stream. A restart path
 * remains available only when the requested target represents different Live content.
 */
object LivePlaybackSurfaceHandoff {
    fun transferAcrossPresentation(
        detachCurrentSurface: () -> Boolean,
        switchPresentation: () -> Unit,
    ): Boolean {
        val detached = detachCurrentSurface()
        switchPresentation()
        return detached
    }

    fun restartAcrossPresentation(
        detachCurrentSurface: () -> Boolean,
        stopPlayback: () -> Unit,
        switchPresentation: () -> Unit,
        startPlayback: () -> Unit,
    ): Boolean {
        val detached = detachCurrentSurface()
        stopPlayback()
        switchPresentation()
        startPlayback()
        return detached
    }
}

/**
 * Stops Live while leaving its current presentation instead of handing off to another surface.
 * The departing PlayerView is detached before the stream is stopped and before Compose clears the
 * presentation state. This prevents a stale Live surface from being reused by a later bind.
 * VOD, Series, and PiP do not use this helper.
 */
object LivePlaybackSurfaceTeardown {
    fun stopAfterDetaching(
        detachCurrentSurface: () -> Boolean,
        stopPlayback: () -> Unit,
        clearPresentation: () -> Unit,
    ): Boolean {
        val detached = detachCurrentSurface()
        stopPlayback()
        clearPresentation()
        return detached
    }
}

enum class LivePlaybackPresentationSurface {
    PREVIEW,
    FULLSCREEN,
}

data class LivePlaybackTransitionTarget(
    val surface: LivePlaybackPresentationSurface,
    val sourceId: String,
    val channelId: String,
) {
    fun isSameContentAs(other: LivePlaybackTransitionTarget): Boolean =
        sourceId == other.sourceId && channelId == other.channelId

    companion object {
        fun preview(selection: LivePlaybackSelection): LivePlaybackTransitionTarget =
            from(selection, LivePlaybackPresentationSurface.PREVIEW)

        fun fullscreen(selection: LivePlaybackSelection): LivePlaybackTransitionTarget =
            from(selection, LivePlaybackPresentationSurface.FULLSCREEN)

        private fun from(
            selection: LivePlaybackSelection,
            surface: LivePlaybackPresentationSurface,
        ): LivePlaybackTransitionTarget = LivePlaybackTransitionTarget(
            surface = surface,
            sourceId = selection.request.sourceId,
            channelId = selection.request.channelId,
        )
    }
}

enum class LivePlaybackTransitionDecision {
    APPLIED,
    DUPLICATE,
}

/**
 * Deduplicates overlapping Live presentation requests without introducing a second playback queue.
 *
 * The gate owns only presentation intent: the last presentation observed by Compose and the most
 * recent handoff requested but not yet acknowledged by composition. Repeated requests for the same
 * pending target are ignored. When there is no pending target, the already-observed destination is
 * also a duplicate. A reverse request while another handoff is pending is accepted immediately.
 *
 * Preview <-> Fullscreen transitions for the same source/channel preserve the current controller
 * and stream. If a future caller requests a different source/channel through this gate, the older
 * detach -> stop -> switch -> start path is retained for content replacement safety.
 */
class LivePlaybackTransitionGate {
    private var observedTarget: LivePlaybackTransitionTarget? = null
    private var requestedTarget: LivePlaybackTransitionTarget? = null

    fun reconcileObserved(target: LivePlaybackTransitionTarget?) {
        observedTarget = target
        if (target == null || requestedTarget == target) {
            requestedTarget = null
        }
    }

    fun requestHandoff(
        target: LivePlaybackTransitionTarget,
        detachCurrentSurface: () -> Boolean,
        stopPlayback: () -> Unit,
        switchPresentation: () -> Unit,
        startPlayback: () -> Unit,
    ): LivePlaybackTransitionDecision {
        val pendingTarget = requestedTarget
        if (
            target == pendingTarget ||
            (pendingTarget == null && target == observedTarget)
        ) {
            return LivePlaybackTransitionDecision.DUPLICATE
        }

        val fromTarget = pendingTarget ?: observedTarget
        val previousRequestedTarget = requestedTarget
        requestedTarget = target
        return try {
            if (fromTarget?.isSameContentAs(target) == true) {
                LivePlaybackSurfaceHandoff.transferAcrossPresentation(
                    detachCurrentSurface = detachCurrentSurface,
                    switchPresentation = switchPresentation,
                )
            } else {
                LivePlaybackSurfaceHandoff.restartAcrossPresentation(
                    detachCurrentSurface = detachCurrentSurface,
                    stopPlayback = stopPlayback,
                    switchPresentation = switchPresentation,
                    startPlayback = startPlayback,
                )
            }
            LivePlaybackTransitionDecision.APPLIED
        } catch (failure: Throwable) {
            if (requestedTarget == target) {
                requestedTarget = previousRequestedTarget
            }
            throw failure
        }
    }
}
