package app.ownplay.player.download

internal enum class OfflineDownloadWriteDisposition {
    WRITE_FROM_ZERO,
    APPEND,
    RESTART,
    FAIL,
}

internal data class OfflineDownloadResponsePlan(
    val disposition: OfflineDownloadWriteDisposition,
    val expectedTotalBytes: Long?,
)

internal object OfflineDownloadResponsePolicy {
    private val contentRangePattern = Regex(
        pattern = """bytes\s+(\d+)-(\d+)/(\d+|\*)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun plan(
        statusCode: Int,
        existingBytes: Long,
        contentRange: String?,
        contentLength: Long?,
    ): OfflineDownloadResponsePlan {
        val normalizedExisting = existingBytes.coerceAtLeast(0L)
        val normalizedLength = contentLength?.takeIf { it >= 0L }
        if (statusCode == 204 || statusCode == 205 || normalizedLength == 0L) {
            return OfflineDownloadResponsePlan(
                disposition = OfflineDownloadWriteDisposition.FAIL,
                expectedTotalBytes = normalizedLength,
            )
        }
        if (statusCode != 206) {
            return OfflineDownloadResponsePlan(
                disposition = OfflineDownloadWriteDisposition.WRITE_FROM_ZERO,
                expectedTotalBytes = normalizedLength,
            )
        }

        val parsed = parseContentRange(contentRange)
            ?: return invalidPartialResponse(normalizedExisting)
        if (parsed.start != normalizedExisting) {
            return invalidPartialResponse(normalizedExisting)
        }
        val rangeLength = parsed.end - parsed.start
        if (rangeLength == Long.MAX_VALUE) {
            return invalidPartialResponse(normalizedExisting)
        }
        val bodyBytes = rangeLength + 1L
        if (normalizedLength != null && normalizedLength != bodyBytes) {
            return invalidPartialResponse(normalizedExisting)
        }
        val totalBytes = parsed.total ?: return invalidPartialResponse(normalizedExisting)
        if (totalBytes <= parsed.end) {
            return invalidPartialResponse(normalizedExisting)
        }

        return OfflineDownloadResponsePlan(
            disposition = if (normalizedExisting > 0L) {
                OfflineDownloadWriteDisposition.APPEND
            } else {
                OfflineDownloadWriteDisposition.WRITE_FROM_ZERO
            },
            expectedTotalBytes = totalBytes,
        )
    }

    private fun invalidPartialResponse(existingBytes: Long): OfflineDownloadResponsePlan =
        OfflineDownloadResponsePlan(
            disposition = if (existingBytes > 0L) {
                OfflineDownloadWriteDisposition.RESTART
            } else {
                OfflineDownloadWriteDisposition.FAIL
            },
            expectedTotalBytes = null,
        )

    private fun parseContentRange(value: String?): ParsedContentRange? {
        val match = value?.trim()?.let(contentRangePattern::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = match.groupValues[3]
            .takeUnless { it == "*" }
            ?.toLongOrNull()
            ?: if (match.groupValues[3] == "*") null else return null
        return ParsedContentRange(start = start, end = end, total = total)
    }

    private data class ParsedContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )
}
