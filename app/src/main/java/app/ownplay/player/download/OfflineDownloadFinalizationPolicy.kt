package app.ownplay.player.download

internal object OfflineDownloadFinalizationPolicy {
    fun recoverableFinalBytes(
        finalized: Boolean,
        actualBytes: Long,
        expectedTotalBytes: Long?,
    ): Long? {
        if (!finalized || actualBytes <= 0L) return null
        if (expectedTotalBytes != null && expectedTotalBytes != actualBytes) return null
        return actualBytes
    }
}
