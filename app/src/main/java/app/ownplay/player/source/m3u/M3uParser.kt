package app.ownplay.player.source.m3u

import java.net.URI
import java.util.Locale

object M3uParser {
    private val attributePattern = Regex(
        """([A-Za-z0-9_-]+)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s]+))""",
    )

    fun parse(text: String): M3uPlaylist = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): M3uPlaylist {
        val entries = mutableListOf<M3uEntry>()
        val epgUrls = linkedSetOf<String>()
        var pending: PendingEntry? = null

        lines.forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    sequenceOf(attributes["url-tvg"], attributes["x-tvg-url"])
                        .filterNotNull()
                        .flatMap { it.split(',').asSequence() }
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(epgUrls::add)
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                }

                line.startsWith('#') -> Unit

                !isPlausibleStreamLocation(line) -> {
                    pending = null
                }

                else -> {
                    val metadata = pending
                    entries += M3uEntry(
                        displayName = metadata?.displayName ?: deriveDisplayName(line),
                        streamUrl = line,
                        tvgId = metadata?.attributes?.get("tvg-id"),
                        tvgName = metadata?.attributes?.get("tvg-name"),
                        logoUrl = metadata?.attributes?.get("tvg-logo"),
                        groupTitle = metadata?.attributes?.get("group-title"),
                        attributes = metadata?.attributes.orEmpty(),
                    )
                    pending = null
                }
            }
        }

        return M3uPlaylist(
            entries = entries,
            epgUrls = epgUrls.toList(),
        )
    }

    private fun parseExtInf(line: String): PendingEntry {
        val payload = line.substringAfter(':', missingDelimiterValue = "")
        val separatorIndex = findFirstUnquotedComma(payload)
        val metadataPart = if (separatorIndex >= 0) payload.substring(0, separatorIndex) else payload
        val explicitName = if (separatorIndex >= 0) payload.substring(separatorIndex + 1).trim() else ""
        val attributes = parseAttributes(metadataPart)
        val displayName = explicitName
            .ifEmpty { attributes["tvg-name"].orEmpty().trim() }
            .ifEmpty { "Unnamed stream" }

        return PendingEntry(
            displayName = displayName,
            attributes = attributes,
        )
    }

    private fun parseAttributes(value: String): Map<String, String> = buildMap {
        attributePattern.findAll(value).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val attributeValue = sequenceOf(2, 3, 4)
                .map { match.groupValues[it] }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .trim()
            put(key, attributeValue)
        }
    }

    private fun findFirstUnquotedComma(value: String): Int {
        var quote: Char? = null
        value.forEachIndexed { index, char ->
            when {
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote != null && char == quote -> quote = null
                quote == null && char == ',' -> return index
            }
        }
        return -1
    }

    private fun isPlausibleStreamLocation(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return !uri.scheme.isNullOrBlank()
    }

    private fun deriveDisplayName(streamUrl: String): String {
        val uri = runCatching { URI(streamUrl) }.getOrNull()
        val pathName = uri?.path
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
        return pathName
            ?: uri?.host?.takeIf(String::isNotBlank)
            ?: "Unnamed stream"
    }

    private data class PendingEntry(
        val displayName: String,
        val attributes: Map<String, String>,
    )
}
