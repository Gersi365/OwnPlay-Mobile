package app.ownplay.mobile.sources.data.xtream

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object XtreamLiveStreamIdentity {
    private const val PREFIX = "xtream://live/"
    private val validExtension = Regex("[a-z0-9]{1,12}")

    fun encode(streamId: String, containerExtension: String?): String {
        val normalizedStreamId = streamId.trim()
        require(normalizedStreamId.isNotBlank()) { "Stream id must not be blank" }

        val base = "$PREFIX${encodePath(normalizedStreamId)}"
        val extension = normalizeExtension(containerExtension) ?: return base
        return "$base?ext=$extension"
    }

    fun preferredSupportedExtension(allowedOutputFormats: List<String>): String? =
        allowedOutputFormats
            .asSequence()
            .mapNotNull(::normalizeExtension)
            .firstOrNull { extension -> extension == "ts" || extension == "m3u8" }

    fun extension(identity: String, streamId: String): String? {
        val normalizedStreamId = streamId.trim()
        if (normalizedStreamId.isBlank()) return null

        val base = "$PREFIX${encodePath(normalizedStreamId)}"
        if (!identity.startsWith(base)) return null

        val suffix = identity.removePrefix(base)
        if (!suffix.startsWith("?ext=")) return null
        val rawExtension = suffix.removePrefix("?ext=")
        if (rawExtension.contains('&') || rawExtension.contains('#')) return null
        return normalizeExtension(rawExtension)
    }

    private fun normalizeExtension(value: String?): String? = value
        ?.trim()
        ?.removePrefix(".")
        ?.lowercase()
        ?.takeIf { it.matches(validExtension) }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
}
