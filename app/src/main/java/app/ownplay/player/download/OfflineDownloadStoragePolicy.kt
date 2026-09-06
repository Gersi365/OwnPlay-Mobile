package app.ownplay.player.download

internal const val OFFLINE_DOWNLOAD_FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L

internal fun measuredUsableSpaceBytes(
    availableBlocks: Long,
    fragmentSizeBytes: Long,
): Long? {
    if (availableBlocks < 0L || fragmentSizeBytes <= 0L) return null
    if (availableBlocks == 0L) return 0L
    if (availableBlocks > Long.MAX_VALUE / fragmentSizeBytes) return Long.MAX_VALUE
    return availableBlocks * fragmentSizeBytes
}

internal fun shouldFailOfflineDownloadPreflight(
    usableSpaceBytes: Long?,
    requiredBytes: Long,
): Boolean = usableSpaceBytes?.let { usable ->
    !hasEnoughOfflineDownloadSpace(
        usableSpaceBytes = usable,
        requiredBytes = requiredBytes,
    )
} ?: false

internal fun hasEnoughOfflineDownloadSpace(
    usableSpaceBytes: Long,
    requiredBytes: Long,
): Boolean {
    val usable = usableSpaceBytes.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    if (usable <= OFFLINE_DOWNLOAD_FREE_SPACE_RESERVE_BYTES) return false
    return required <= usable - OFFLINE_DOWNLOAD_FREE_SPACE_RESERVE_BYTES
}
