package app.ownplay.player.source.m3u

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class M3uSourceLocator(
    val endpoint: String,
    val allowCleartext: Boolean,
    val epgUrls: List<String>,
) {
    init {
        require(endpoint.isNotBlank()) { "M3U endpoint must not be blank" }
    }

    override fun toString(): String =
        "M3uSourceLocator(endpoint=<redacted>, allowCleartext=$allowCleartext, " +
            "epgUrls=${if (epgUrls.isEmpty()) "empty" else "<redacted>"})"
}

/**
 * Versioned storage format for M3U source metadata.
 *
 * Older OwnPlay builds stored only the raw playlist URL/document URI. Callers should
 * use [parseOrLegacy] so those existing sources continue to work without a migration.
 */
object M3uSourceLocatorCodec {
    private const val PREFIX = "ownplay-m3u-v1"

    fun encode(locator: M3uSourceLocator): String {
        val endpoint = locator.endpoint.trim()
        require(endpoint.isNotEmpty()) { "M3U endpoint must not be blank" }
        val epgUrls = locator.epgUrls.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(separator = ",") { encodeComponent(it) }
        return buildString {
            append(PREFIX)
            append('|')
            append(encodeComponent(endpoint))
            append('|')
            append(if (locator.allowCleartext) '1' else '0')
            append('|')
            append(epgUrls)
        }
    }

    fun parse(value: String): M3uSourceLocator? {
        val parts = value.split('|', limit = 4)
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val endpoint = decodeComponent(parts[1])?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val allowCleartext = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val epgUrls = if (parts[3].isBlank()) {
            emptyList()
        } else {
            parts[3].split(',').mapNotNull { encoded ->
                decodeComponent(encoded)?.trim()?.takeIf(String::isNotEmpty)
            }.distinct()
        }
        return M3uSourceLocator(
            endpoint = endpoint,
            allowCleartext = allowCleartext,
            epgUrls = epgUrls,
        )
    }

    fun parseOrLegacy(value: String): M3uSourceLocator =
        parseOrLegacy(value = value, allowCleartext = false)

    fun parseOrLegacy(
        value: String,
        allowCleartext: Boolean,
    ): M3uSourceLocator = parse(value) ?: M3uSourceLocator(
        endpoint = value.trim(),
        allowCleartext = allowCleartext,
        epgUrls = emptyList(),
    )

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeComponent(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()
}
