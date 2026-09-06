package app.ownplay.player.persistence.reconcile

import app.ownplay.player.source.m3u.M3uEntry
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object ProviderIdentity {
    fun xtreamLiveStream(streamId: Int): String {
        require(streamId > 0) { "Xtream stream ID must be positive" }
        return "xtream:live:$streamId"
    }

    fun m3u(entry: M3uEntry): String {
        val stableMetadata = when {
            !entry.tvgId.isNullOrBlank() -> "tvg-id:${normalize(entry.tvgId)}"
            !entry.tvgName.isNullOrBlank() -> buildString {
                append("tvg-name:")
                append(normalize(entry.tvgName))
                append("|group:")
                append(normalize(entry.groupTitle.orEmpty()))
                append("|name:")
                append(normalize(entry.displayName))
            }
            else -> "fallback:${fallbackLocatorSignature(entry.streamUrl)}|name:${normalize(entry.displayName)}"
        }

        return "m3u:${sha256(stableMetadata)}"
    }

    private fun fallbackLocatorSignature(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        if (uri == null) return sha256(rawUrl.trim())

        val signature = buildString {
            append(uri.scheme?.lowercase(Locale.ROOT).orEmpty())
            append("://")
            append(uri.host?.lowercase(Locale.ROOT).orEmpty())
            if (uri.port >= 0) {
                append(':')
                append(uri.port)
            }
            append(uri.path.orEmpty())
        }
        return sha256(signature)
    }

    private fun normalize(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
