package app.ownplay.player.download

internal enum class OfflineDownloadFailureDisposition {
    RETRY,
    RESTART,
    FAIL,
}

internal object OfflineDownloadRetryPolicy {
    fun forHttpStatus(
        statusCode: Int,
        hasPartialContent: Boolean = false,
    ): OfflineDownloadFailureDisposition = when {
        statusCode == 416 && hasPartialContent -> OfflineDownloadFailureDisposition.RESTART
        statusCode == 408 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode == 500 ||
            statusCode == 502 ||
            statusCode == 503 ||
            statusCode == 504 -> OfflineDownloadFailureDisposition.RETRY
        else -> OfflineDownloadFailureDisposition.FAIL
    }
}
