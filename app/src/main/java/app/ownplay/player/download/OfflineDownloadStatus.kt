package app.ownplay.player.download

internal fun queuedDownloadStatusLabel(failureReason: String?): String {
    val reason = failureReason?.trim()?.takeIf(String::isNotBlank)
    return if (reason == null) {
        "Queued"
    } else {
        "Retry scheduled · $reason"
    }
}
