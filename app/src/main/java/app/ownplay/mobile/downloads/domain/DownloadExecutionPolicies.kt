package app.ownplay.mobile.downloads.domain

object DownloadFilePolicy {
    private val extensionPattern = Regex("^[A-Za-z0-9]{1,8}$")
    private val forbiddenSegmentChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun normalizeFiniteExtension(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase()
            ?.takeIf(extensionPattern::matches)
            ?: return null
        return normalized.takeUnless { it in NON_FINITE_EXTENSIONS }
    }

    fun safeSegment(value: String?, fallback: String): String {
        val candidate = value
            ?.replace(forbiddenSegmentChars, "_")
            ?.trim()
            ?.trim('.')
            ?.replace(Regex("\\s+"), " ")
            ?.take(MAX_SEGMENT_LENGTH)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return candidate ?: fallback
    }

    fun fileName(
        title: String,
        extension: String,
        identitySuffix: String,
    ): String {
        val safeTitle = safeSegment(title, "Media")
        val safeExtension = normalizeFiniteExtension(extension)
            ?: throw IllegalArgumentException("Download extension must be finite and safe")
        val suffix = identitySuffix
            .filter(Char::isLetterOrDigit)
            .takeLast(12)
            .ifBlank { "item" }
        return "$safeTitle - $suffix.$safeExtension"
    }

    fun episodeFileName(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        extension: String,
        identitySuffix: String,
    ): String {
        val prefix = "S${seasonNumber.coerceAtLeast(0).toString().padStart(2, '0')}E${episodeNumber.coerceAtLeast(0).toString().padStart(2, '0')}"
        return fileName("$prefix - $title", extension, identitySuffix)
    }

    fun seasonDirectory(seasonNumber: Int): String =
        "Season ${seasonNumber.coerceAtLeast(0).toString().padStart(2, '0')}"

    private val NON_FINITE_EXTENSIONS = setOf("m3u", "m3u8")
    private const val MAX_SEGMENT_LENGTH = 96
}

object DownloadPendingNamePolicy {
    fun stagingDisplayName(downloadId: DownloadId): String {
        val token = downloadId.value
            .removePrefix("download:")
            .filter(Char::isLetterOrDigit)
            .takeLast(32)
            .ifBlank { "download" }
        return "ownplay-$token.part"
    }
}

object DownloadTransferIntegrityPolicy {
    fun isValid(
        transferredBytes: Long,
        expectedBytes: Long?,
        storedBytes: Long?,
    ): Boolean =
        transferredBytes > 0L &&
            (expectedBytes == null || expectedBytes == transferredBytes) &&
            storedBytes != null &&
            storedBytes == transferredBytes
}

class DownloadProgressThrottlePolicy(
    private val minByteDelta: Long = 256L * 1024L,
    private val minTimeDeltaMs: Long = 750L,
) {
    init {
        require(minByteDelta > 0L) { "Progress byte delta must be positive" }
        require(minTimeDeltaMs > 0L) { "Progress time delta must be positive" }
    }

    fun shouldPublish(
        previousBytes: Long,
        previousAtMs: Long,
        currentBytes: Long,
        currentAtMs: Long,
        final: Boolean = false,
    ): Boolean {
        if (final) return true
        if (currentBytes < previousBytes || currentAtMs < previousAtMs) return false
        return currentBytes - previousBytes >= minByteDelta ||
            currentAtMs - previousAtMs >= minTimeDeltaMs
    }
}
