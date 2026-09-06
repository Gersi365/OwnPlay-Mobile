package app.ownplay.player.download

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.webkit.MimeTypeMap
import app.ownplay.player.persistence.download.MediaDownloadEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

internal object OfflineDownloadStorage {
    private const val PRIVATE_DIRECTORY = "offline"
    private const val MEDIASTORE_URI_PREFIX = "content://media/"
    private const val PENDING_DOWNLOAD_MARKER_PREFIX = "ownplay://offline-download/"

    fun supportsPublicDownloads(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isPublicDownloadsLocation(location: String?): Boolean =
        location?.startsWith(MEDIASTORE_URI_PREFIX) == true

    fun usableSpaceBytes(
        context: Context,
        destinationLocation: String?,
    ): Long? = if (isPublicDownloadsLocation(destinationLocation)) {
        publicDestinationUsableSpaceBytes(
            context = context,
            location = requireNotNull(destinationLocation),
        )
    } else {
        privateDirectory(context).usableSpace.coerceAtLeast(0L)
    }

    fun partialFile(context: Context, downloadId: String): File =
        File(privateDirectory(context), "$downloadId.part")

    fun privateFinalFile(context: Context, downloadId: String, extension: String): File =
        File(privateDirectory(context), "$downloadId.${normalizeExtension(extension)}")

    fun privateRelativePath(file: File): String = "$PRIVATE_DIRECTORY/${file.name}"

    fun resolvePrivateRelativePath(context: Context, relativePath: String): File? {
        if (!relativePath.startsWith("$PRIVATE_DIRECTORY/")) return null
        val base = privateDirectory(context).canonicalFile
        val candidate = File(context.filesDir, relativePath).canonicalFile
        return candidate.takeIf { file ->
            file.path == base.path || file.path.startsWith(base.path + File.separator)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    fun createPublicDownloadsDestination(
        context: Context,
        row: MediaDownloadEntity,
    ): String {
        check(supportsPublicDownloads()) { "Public Downloads requires Android 10 or newer" }
        findOwnedPendingPublicDownloadsDestination(context, row.downloadId)?.let { return it }

        val extension = normalizeExtension(row.containerExtension)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, publicDisplayName(row, extension))
            put(MediaStore.Downloads.MIME_TYPE, mimeType(extension))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.DOWNLOAD_URI, pendingDownloadMarker(row.downloadId))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: throw IOException("Could not create file in Downloads")
        return uri.toString()
    }

    fun openPublicOutput(
        context: Context,
        location: String,
        append: Boolean,
        startBytes: Long,
    ): BufferedOutputStream {
        val uri = Uri.parse(location)
        val descriptor = context.contentResolver.openFileDescriptor(
            uri,
            if (append) "rw" else "rwt",
        ) ?: throw IOException("Downloaded file is unavailable")
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        if (append && startBytes > 0L) {
            output.channel.position(startBytes)
        }
        return BufferedOutputStream(output)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    fun publishPublicDownload(context: Context, location: String) {
        if (!isPublicDownloadsLocation(location)) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val updated = context.contentResolver.update(Uri.parse(location), values, null, null)
        if (updated <= 0) throw IOException("Could not publish file in Downloads")
    }

    @TargetApi(Build.VERSION_CODES.Q)
    fun isPublishedPublicDownload(context: Context, location: String?): Boolean? {
        if (!supportsPublicDownloads() || !isPublicDownloadsLocation(location)) return null
        val resolved = location ?: return null
        return try {
            context.contentResolver.query(
                Uri.parse(resolved),
                arrayOf(MediaStore.Downloads.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val pendingIndex = cursor.getColumnIndex(MediaStore.Downloads.IS_PENDING)
                if (pendingIndex < 0) null else cursor.getInt(pendingIndex) == 0
            }
        } catch (_: Exception) {
            null
        }
    }

    fun locationExists(context: Context, location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        return if (isPublicDownloadsLocation(location)) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { true } ?: false
            } catch (_: Exception) {
                false
            }
        } else {
            resolvePrivateRelativePath(context, location)?.isFile == true
        }
    }

    fun locationSize(context: Context, location: String?): Long? {
        if (location.isNullOrBlank()) return null
        return if (isPublicDownloadsLocation(location)) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { descriptor ->
                    descriptor.statSize.takeIf { it >= 0L }
                }
            } catch (_: Exception) {
                null
            }
        } else {
            resolvePrivateRelativePath(context, location)
                ?.takeIf(File::isFile)
                ?.length()
        }
    }

    fun deleteLocation(context: Context, location: String?) {
        if (location.isNullOrBlank()) return
        if (isPublicDownloadsLocation(location)) {
            try {
                context.contentResolver.delete(Uri.parse(location), null, null)
            } catch (_: Exception) {
                Unit
            }
        } else {
            resolvePrivateRelativePath(context, location)?.delete()
        }
    }

    fun playbackUri(context: Context, location: String?): String? {
        if (!locationExists(context, location)) return null
        val resolved = location ?: return null
        return if (isPublicDownloadsLocation(resolved)) {
            resolved
        } else {
            resolvePrivateRelativePath(context, resolved)?.let(Uri::fromFile)?.toString()
        }
    }

    fun normalizeExtension(extension: String?): String = extension
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: "mp4"

    internal fun safeFileStem(value: String): String {
        val cleaned = value
            .map { char -> if (char.isISOControl()) ' ' else char }
            .joinToString(separator = "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(120).ifBlank { "OwnPlay" }
    }

    internal fun pendingDownloadMarker(downloadId: String): String =
        "$PENDING_DOWNLOAD_MARKER_PREFIX$downloadId"

    internal fun isOwnedPendingDownloadCandidate(
        ownerPackageName: String?,
        expectedPackageName: String,
        downloadUri: String?,
        expectedDownloadId: String,
    ): Boolean =
        ownerPackageName == expectedPackageName &&
            downloadUri == pendingDownloadMarker(expectedDownloadId)

    private fun privateDirectory(context: Context): File =
        File(context.filesDir, PRIVATE_DIRECTORY).apply { mkdirs() }

    @TargetApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION")
    private fun findOwnedPendingPublicDownloadsDestination(
        context: Context,
        downloadId: String,
    ): String? {
        val marker = pendingDownloadMarker(downloadId)
        val queryUri = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.OWNER_PACKAGE_NAME,
            MediaStore.Downloads.DOWNLOAD_URI,
            MediaStore.Downloads.IS_PENDING,
        )
        val selection =
            "${MediaStore.Downloads.IS_PENDING} = 1 AND ${MediaStore.Downloads.DOWNLOAD_URI} = ?"
        return try {
            context.contentResolver.query(
                queryUri,
                projection,
                selection,
                arrayOf(marker),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Downloads._ID)
                val ownerIndex = cursor.getColumnIndex(MediaStore.Downloads.OWNER_PACKAGE_NAME)
                val downloadUriIndex = cursor.getColumnIndex(MediaStore.Downloads.DOWNLOAD_URI)
                val pendingIndex = cursor.getColumnIndex(MediaStore.Downloads.IS_PENDING)
                if (idIndex < 0 || ownerIndex < 0 || downloadUriIndex < 0 || pendingIndex < 0) {
                    return@use null
                }
                while (cursor.moveToNext()) {
                    if (cursor.getInt(pendingIndex) != 1) continue
                    val ownerPackageName = cursor.getString(ownerIndex)
                    val downloadUri = cursor.getString(downloadUriIndex)
                    if (
                        isOwnedPendingDownloadCandidate(
                            ownerPackageName = ownerPackageName,
                            expectedPackageName = context.packageName,
                            downloadUri = downloadUri,
                            expectedDownloadId = downloadId,
                        )
                    ) {
                        val mediaId = cursor.getLong(idIndex)
                        return@use ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            mediaId,
                        ).toString()
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun publicDestinationUsableSpaceBytes(
        context: Context,
        location: String,
    ): Long? = try {
        context.contentResolver.openFileDescriptor(Uri.parse(location), "rw")?.use { descriptor ->
            val stats = Os.fstatvfs(descriptor.fileDescriptor)
            val fragmentSize = stats.f_frsize.takeIf { it > 0L } ?: stats.f_bsize
            measuredUsableSpaceBytes(
                availableBlocks = stats.f_bavail,
                fragmentSizeBytes = fragmentSize,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun publicDisplayName(row: MediaDownloadEntity, extension: String): String {
        val stem = if (
            !row.seriesTitle.isNullOrBlank() &&
            row.seasonNumber != null &&
            row.episodeNumber != null
        ) {
            val season = row.seasonNumber.toString().padStart(2, '0')
            val episode = row.episodeNumber.toString().padStart(2, '0')
            safeFileStem("${row.seriesTitle} - S${season}E${episode} - ${row.title}")
        } else {
            safeFileStem(row.title)
        }
        return "$stem.$extension"
    }

    private fun mimeType(extension: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "video/*"
}
