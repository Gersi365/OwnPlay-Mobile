package app.ownplay.mobile.downloads.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadOrigin
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidDownloadedMediaVerifier(context: Context) : DownloadedMediaVerifier, DownloadedMediaAvailabilityProbe {
    private val resolver = context.applicationContext.contentResolver
    private val completedRoot = File(context.applicationContext.filesDir, "ownplay-downloads/completed")

    override suspend fun checkAvailability(item: DownloadItem): DownloadedMediaAvailability =
        withContext(Dispatchers.IO) {
            val reference = item.localReference ?: return@withContext DownloadedMediaAvailability.MISSING
            val expectedBytes = item.verifiedBytes ?: return@withContext DownloadedMediaAvailability.MISSING
            try {
                val uri = Uri.parse(reference)
                when (uri.scheme) {
                    "content" -> {
                        if (!contentReferenceIsAllowed(item, uri)) {
                            DownloadedMediaAvailability.MISSING
                        } else {
                            contentAvailability(uri, expectedBytes)
                        }
                    }
                    "file" -> fileAvailability(item, uri, expectedBytes)
                    else -> DownloadedMediaAvailability.MISSING
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                DownloadedMediaAvailability.UNREADABLE
            } catch (_: IOException) {
                DownloadedMediaAvailability.UNREADABLE
            } catch (_: Exception) {
                DownloadedMediaAvailability.UNREADABLE
            }
        }

    override suspend fun verify(item: DownloadItem): Boolean = withContext(Dispatchers.IO) {
        val reference = item.localReference ?: return@withContext false
        val expectedBytes = item.verifiedBytes ?: return@withContext false
        try {
            val uri = Uri.parse(reference)
            if (item.origin == DownloadOrigin.IMPORTED_EXTERNAL && item.sha256 == null) {
                return@withContext uri.scheme == "content" &&
                    contentReferenceIsAllowed(item, uri) &&
                    contentSizeMatches(uri, expectedBytes)
            }
            val input = when (uri.scheme) {
                "content" -> {
                    if (!contentReferenceIsAllowed(item, uri)) return@withContext false
                    resolver.openInputStream(uri)
                }
                "file" -> {
                    if (item.origin != DownloadOrigin.APP_MANAGED) return@withContext false
                    val file = uri.path?.let(::File)?.canonicalFile ?: return@withContext false
                    if (!file.path.startsWith(completedRoot.canonicalPath + File.separator)) return@withContext false
                    file.inputStream()
                }
                else -> return@withContext false
            } ?: return@withContext false
            input.use { verifyDownloadedBytes(it, expectedBytes, item.sha256) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun fileAvailability(
        item: DownloadItem,
        uri: Uri,
        expectedBytes: Long,
    ): DownloadedMediaAvailability {
        if (item.origin != DownloadOrigin.APP_MANAGED) return DownloadedMediaAvailability.MISSING
        val file = uri.path?.let(::File)?.canonicalFile ?: return DownloadedMediaAvailability.MISSING
        if (!file.path.startsWith(completedRoot.canonicalPath + File.separator)) {
            return DownloadedMediaAvailability.MISSING
        }
        if (!file.exists() || !file.isFile || file.length() != expectedBytes) {
            return DownloadedMediaAvailability.MISSING
        }
        return DownloadedMediaAvailability.AVAILABLE
    }

    private fun contentAvailability(
        uri: Uri,
        expectedBytes: Long,
    ): DownloadedMediaAvailability {
        if (expectedBytes <= 0L) return DownloadedMediaAvailability.MISSING
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0L) {
                    return if (descriptor.length == expectedBytes) {
                        DownloadedMediaAvailability.AVAILABLE
                    } else {
                        DownloadedMediaAvailability.MISSING
                    }
                }
            }
        } catch (_: FileNotFoundException) {
            return DownloadedMediaAvailability.MISSING
        } catch (_: SecurityException) {
            return DownloadedMediaAvailability.UNREADABLE
        } catch (_: IOException) {
            return DownloadedMediaAvailability.UNREADABLE
        }

        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use DownloadedMediaAvailability.MISSING
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) {
                    DownloadedMediaAvailability.UNREADABLE
                } else if (cursor.getLong(index) == expectedBytes) {
                    DownloadedMediaAvailability.AVAILABLE
                } else {
                    DownloadedMediaAvailability.MISSING
                }
            } ?: DownloadedMediaAvailability.MISSING
        } catch (_: FileNotFoundException) {
            DownloadedMediaAvailability.MISSING
        } catch (_: SecurityException) {
            DownloadedMediaAvailability.UNREADABLE
        } catch (_: IOException) {
            DownloadedMediaAvailability.UNREADABLE
        }
    }

    private fun contentReferenceIsAllowed(item: DownloadItem, uri: Uri): Boolean =
        DownloadedContentReferencePolicy.isAllowed(item.origin, uri.authority)

    private fun contentSizeMatches(uri: Uri, expectedBytes: Long): Boolean {
        if (expectedBytes <= 0L) return false
        val descriptorLength = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length }
        }.getOrNull()
        if (descriptorLength != null && descriptorLength >= 0L) return descriptorLength == expectedBytes
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                index >= 0 && !cursor.isNull(index) && cursor.getLong(index) == expectedBytes
            } ?: false
        }.getOrDefault(false)
    }
}
