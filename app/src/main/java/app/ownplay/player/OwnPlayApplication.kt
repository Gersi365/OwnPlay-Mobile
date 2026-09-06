package app.ownplay.player

import android.app.Application

/**
 * Process-scoped owner for the app runtime.
 *
 * Playlist imports and their Room connection must not be cancelled just because MainActivity is
 * destroyed. Android will tear this owner down with the process; interrupted pending imports are
 * already persisted and are resumed on the next process start.
 */
class OwnPlayApplication : Application() {
    val runtime: OwnPlayAppRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OwnPlayAppRuntime(this)
    }
}
