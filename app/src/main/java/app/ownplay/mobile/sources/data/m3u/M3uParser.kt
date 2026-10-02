package app.ownplay.mobile.sources.data.m3u

object M3uParser {
    private const val MAX_ENTRIES = 200_000
    private const val MAX_LINE_LENGTH = 16_384
    private val attributeRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(content: String): M3uParseResult {
        val entries = ArrayList<M3uEntry>()
        var skipped = 0
        var pending: PendingEntry? = null

        content.lineSequence().forEach { rawLine ->
            if (entries.size >= MAX_ENTRIES) {
                return@forEach
            }

            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEach
            }
            if (line.length > MAX_LINE_LENGTH) {
                pending = null
                skipped += 1
                return@forEach
            }

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                    if (pending == null) {
                        skipped += 1
                    }
                }

                line.startsWith("#") -> Unit

                pending != null -> {
                    val metadata = pending ?: return@forEach
                    if (isSupportedStreamLocator(line)) {
                        entries += M3uEntry(
                            name = metadata.name,
                            groupTitle = metadata.attributes["group-title"].normalizedOrNull(),
                            tvgId = metadata.attributes["tvg-id"].normalizedOrNull(),
                            tvgName = metadata.attributes["tvg-name"].normalizedOrNull(),
                            logoUrl = metadata.attributes["tvg-logo"].normalizedOrNull(),
                            streamUrl = line,
                            catchUpMode = metadata.attributes["catchup"].normalizedOrNull(),
                            catchUpDays = metadata.attributes["catchup-days"].normalizedPositiveIntOrNull(),
                            catchUpSource = metadata.attributes["catchup-source"].normalizedOrNull(),
                        )
                    } else {
                        skipped += 1
                    }
                    pending = null
                }

                else -> skipped += 1
            }
        }

        if (pending != null) {
            skipped += 1
        }

        return M3uParseResult(
            entries = entries,
            skippedEntries = skipped,
        )
    }

    private fun parseExtInf(line: String): PendingEntry? {
        val commaIndex = findMetadataComma(line)
        if (commaIndex < 0 || commaIndex >= line.lastIndex) {
            return null
        }

        val metadataPart = line.substring(0, commaIndex)
        val name = line.substring(commaIndex + 1).trim()
        if (name.isBlank()) {
            return null
        }

        val attributes = attributeRegex
            .findAll(metadataPart)
            .associate { match ->
                match.groupValues[1].lowercase() to match.groupValues[2]
            }

        return PendingEntry(
            name = name,
            attributes = attributes,
        )
    }

    private fun findMetadataComma(line: String): Int {
        var quoted = false
        line.forEachIndexed { index, char ->
            when (char) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return index
            }
        }
        return -1
    }

    private fun isSupportedStreamLocator(value: String): Boolean {
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("udp://")
    }

    private fun String?.normalizedOrNull(): String? =
        this?.trim()?.takeIf(String::isNotBlank)

    private fun String?.normalizedPositiveIntOrNull(): Int? =
        this?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private data class PendingEntry(
        val name: String,
        val attributes: Map<String, String>,
    )
}