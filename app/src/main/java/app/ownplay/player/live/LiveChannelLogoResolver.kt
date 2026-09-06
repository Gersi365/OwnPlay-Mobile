package app.ownplay.player.live

import android.content.Context
import app.ownplay.player.persistence.secure.AndroidKeystoreSensitiveValueStore
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LiveChannelLogoResolver(
    private val sensitiveValueStore: SensitiveValueStore,
) {
    constructor(context: Context) : this(sharedStore(context.applicationContext))

    suspend fun resolve(logoRef: String?): String? {
        val normalizedRef = logoRef
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null

        return withContext(Dispatchers.IO) {
            try {
                sensitiveValueStore
                    .get(SensitiveValueRef(normalizedRef))
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        @Volatile
        private var processStore: SensitiveValueStore? = null

        fun sharedStore(context: Context): SensitiveValueStore =
            processStore ?: synchronized(this) {
                processStore ?: AndroidKeystoreSensitiveValueStore(context).also { store ->
                    processStore = store
                }
            }
    }
}
