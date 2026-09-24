package app.ownplay.mobile.downloads.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.ownplay.mobile.downloads.domain.DownloadItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidDownloadedMediaVerifier(context: Context) : DownloadedMediaVerifier, DownloadedMediaAvailabilityProbe {
    private val resolver = context.applicationContext.contentResolver
    private val completedRoot = File(context.applicationContext.filesDir, "ownplay-downloads/completed")

    override suspend fun isAvailable(item: DownloadItem): Boolean = withContext(Dispatchers.IO) {
        val reference = item.localReference ?: return@withContext false
        val expectedBytes = item.verifiedBytes ?: return@withContext false
        try {
            val uri = Uri.parse(reference)
            when (uri.scheme) {
                "content" -> {
                    if (uri.authority != MediaStore.AUTHORITY) return@withContext false
                    resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.length < 0L || descriptor.length == expectedBytes
                    } ?: false
                }
                "file" -> {
                    val file = uri.path?.let(::File)?.canonicalFile ?: return@withContext false
                    file.path.startsWith(completedRoot.canonicalPath + File.separator) &&
                        file.isFile && file.length() == expectedBytes
                }
                else -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun verify(item: DownloadItem): Boolean = withContext(Dispatchers.IO) {
        val reference = item.localReference ?: return@withContext false
        val expectedBytes = item.verifiedBytes ?: return@withContext false
        try {
            val uri = Uri.parse(reference)
            val input = when (uri.scheme) {
                "content" -> {
                    if (uri.authority != MediaStore.AUTHORITY) return@withContext false
                    resolver.openInputStream(uri)
                }
                "file" -> {
                    val file = uri.path?.let(::File)?.canonicalFile ?: return@withContext false
                    if (!file.path.startsWith(completedRoot.canonicalPath + File.separator)) {
                        return@withContext false
                    }
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
}
