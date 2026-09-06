package app.ownplay.player

import app.ownplay.player.playback.LivePlaybackPresentationSession
import java.util.WeakHashMap

private val livePlaybackPresentationSessions =
    WeakHashMap<OwnPlayAppRuntime, LivePlaybackPresentationSession>()

/**
 * Process-scoped presentation sidecar for the process-scoped runtime.
 *
 * The weak key prevents this presentation-only state from extending a runtime's lifetime. A new
 * process receives a new runtime and therefore a fresh session, so this cannot restore Live playback
 * after process death.
 */
internal val OwnPlayAppRuntime.livePlaybackPresentationSession: LivePlaybackPresentationSession
    get() = synchronized(livePlaybackPresentationSessions) {
        livePlaybackPresentationSessions.getOrPut(this) {
            LivePlaybackPresentationSession()
        }
    }
