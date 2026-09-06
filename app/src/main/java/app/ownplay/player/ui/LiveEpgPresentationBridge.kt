package app.ownplay.player.ui

import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.epg.EpgSnapshot
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ephemeral UI-only handoff between Preview EPG and Live fullscreen presentation.
 *
 * The bridge never creates a repository or player. It keeps only a weak reference to the existing
 * app runtime so fullscreen can resolve the exact channel EPG when Preview -> Fullscreen happens
 * before the Preview lookup has completed. No provider credentials, stream URLs or persistent user
 * data are stored here.
 */
internal object LiveEpgPresentationBridge {
    private val _snapshot = MutableStateFlow<EpgSnapshot?>(null)
    val snapshot: StateFlow<EpgSnapshot?> = _snapshot.asStateFlow()

    @Volatile
    private var runtimeRef: WeakReference<OwnPlayAppRuntime>? = null

    @Volatile
    private var fullGuideRequested = false

    fun bindRuntime(runtime: OwnPlayAppRuntime) {
        runtimeRef = WeakReference(runtime)
    }

    fun publish(snapshot: EpgSnapshot?) {
        _snapshot.value = snapshot
    }

    suspend fun loadSnapshot(
        sourceId: String,
        channelId: String,
    ): EpgSnapshot? = try {
        runtimeRef
            ?.get()
            ?.epgSnapshot(
                sourceId = sourceId,
                channelId = channelId,
            )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun requestFullGuide() {
        fullGuideRequested = true
    }

    @Synchronized
    fun consumeFullGuideRequest(): Boolean {
        if (!fullGuideRequested) return false
        fullGuideRequested = false
        return true
    }
}
