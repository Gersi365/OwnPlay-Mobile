package app.ownplay.player.download

internal object DownloadNotificationOrder {
    fun eventTime(createdAtEpochMillis: Long): Long = createdAtEpochMillis.coerceAtLeast(0L)

    /** Ascending lexical keys match newest-created first, with a stable identity tie-break. */
    fun sortKey(createdAtEpochMillis: Long, downloadId: String): String =
        "${(Long.MAX_VALUE - eventTime(createdAtEpochMillis)).toString().padStart(19, '0')}:$downloadId"
}
