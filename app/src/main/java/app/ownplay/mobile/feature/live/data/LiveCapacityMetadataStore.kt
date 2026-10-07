package app.ownplay.mobile.feature.live.data

import android.content.Context
import app.ownplay.mobile.feature.live.domain.LiveCapacityMetadata
import java.net.URI
import java.security.MessageDigest

/** Stores only an opaque account fingerprint and a validated positive Live connection limit. */
class LiveCapacityMetadataStore(context: Context) : LiveCapacityMetadata {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun recordXtream(
        sourceId: String,
        baseLocator: String,
        username: String,
        maxConnections: Int?,
    ) {
        val fingerprint = accountFingerprint(baseLocator, username) ?: "source:$sourceId"
        val editor = preferences.edit().putString(key(sourceId, ACCOUNT_SUFFIX), fingerprint)
        if (maxConnections != null && maxConnections > 0) {
            editor.putInt(key(sourceId, LIMIT_SUFFIX), maxConnections)
        } else {
            editor.remove(key(sourceId, LIMIT_SUFFIX))
        }
        editor.apply()
    }

    @Synchronized
    fun clear(sourceId: String) {
        preferences.edit()
            .remove(key(sourceId, ACCOUNT_SUFFIX))
            .remove(key(sourceId, LIMIT_SUFFIX))
            .apply()
    }

    override fun accountKey(sourceId: String): String =
        preferences.getString(key(sourceId, ACCOUNT_SUFFIX), null)
            ?.takeIf(String::isNotBlank)
            ?: "source:$sourceId"

    override fun maxConnections(sourceId: String): Int? =
        preferences.getInt(key(sourceId, LIMIT_SUFFIX), 0).takeIf { it > 0 }

    private fun key(sourceId: String, suffix: String): String = "$sourceId:$suffix"

    private fun accountFingerprint(baseLocator: String, username: String): String? {
        val normalized = runCatching {
            val uri = URI(baseLocator)
            val host = uri.host?.lowercase() ?: return@runCatching null
            URI(
                uri.scheme?.lowercase(),
                null,
                host,
                uri.port,
                uri.path.orEmpty().trimEnd('/'),
                null,
                null,
            ).normalize().toASCIIString()
        }.getOrNull()
        if (normalized.isNullOrBlank() || username.isBlank()) return null
        return "xtream:" + sha256("$normalized\n$username")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES_NAME = "ownplay_live_capacity"
        const val ACCOUNT_SUFFIX = "account"
        const val LIMIT_SUFFIX = "max_connections"
    }
}
