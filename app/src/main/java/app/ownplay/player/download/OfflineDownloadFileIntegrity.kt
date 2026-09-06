package app.ownplay.player.download

import android.content.Context
import app.ownplay.player.persistence.download.MediaDownloadEntity

internal const val OFFLINE_FILE_MISSING_REASON = "Downloaded file is missing"
internal const val OFFLINE_FILE_INCOMPLETE_REASON = "Downloaded file is incomplete"

/**
 * Verifies that a row marked COMPLETED still points at finalized, non-empty content and, when a
 * total size was persisted, that the local file has not been truncated or otherwise resized.
 */
internal object OfflineDownloadFileIntegrity {
    fun verifiedBytes(
        context: Context,
        row: MediaDownloadEntity,
    ): Long? {
        val location = row.localRelativePath ?: return null
        val actualBytes = OfflineDownloadStorage.locationSize(context, location) ?: return null
        val finalized = if (OfflineDownloadStorage.isPublicDownloadsLocation(location)) {
            OfflineDownloadStorage.isPublishedPublicDownload(context, location) == true
        } else {
            OfflineDownloadStorage.locationExists(context, location)
        }
        return OfflineDownloadFinalizationPolicy.recoverableFinalBytes(
            finalized = finalized,
            actualBytes = actualBytes,
            expectedTotalBytes = row.totalBytes,
        )
    }

    fun failureReason(
        context: Context,
        row: MediaDownloadEntity,
    ): String = if (
        OfflineDownloadStorage.locationExists(context, row.localRelativePath)
    ) {
        OFFLINE_FILE_INCOMPLETE_REASON
    } else {
        OFFLINE_FILE_MISSING_REASON
    }
}
