package app.ownplay.player

import app.ownplay.player.playback.OnDemandPresentationSession
import java.util.WeakHashMap

private val onDemandPresentationSessions =
    WeakHashMap<OwnPlayAppRuntime, OnDemandPresentationSession>()

/**
 * Process-scoped presentation sidecar for online Movies and Series.
 *
 * A recreated Activity receives the same session while the process/runtime remains alive. The weak
 * key does not extend runtime lifetime, and a new process receives an empty session, so this cannot
 * restore or autoplay on-demand playback after process death.
 */
internal val OwnPlayAppRuntime.onDemandPresentationSession: OnDemandPresentationSession
    get() = synchronized(onDemandPresentationSessions) {
        onDemandPresentationSessions.getOrPut(this) {
            OnDemandPresentationSession()
        }
    }
