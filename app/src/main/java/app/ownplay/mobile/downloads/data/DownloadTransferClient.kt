package app.ownplay.mobile.downloads.data

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class DownloadTransferFailure {
    NETWORK,
    TIMEOUT,
    SOURCE_UNAVAILABLE,
}

internal class DownloadTransferException(
    val failure: DownloadTransferFailure,
    cause: Throwable? = null,
) : IOException("Download transfer failed", cause)

internal data class DownloadTransferResult(
    val bytesTransferred: Long,
    val reportedContentLength: Long?,
    val sha256: String,
)

internal interface DownloadTransferClient {
    suspend fun transfer(
        uri: String,
        output: OutputStream,
        onProgress: suspend (bytesTransferred: Long, totalBytes: Long?) -> Unit,
    ): DownloadTransferResult
}

internal class OkHttpDownloadTransferClient(
    private val client: OkHttpClient = defaultClient(),
) : DownloadTransferClient {
    override suspend fun transfer(
        uri: String,
        output: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadTransferResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(uri).get().build()
        } catch (error: IllegalArgumentException) {
            throw DownloadTransferException(DownloadTransferFailure.SOURCE_UNAVAILABLE, error)
        }

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val failure = when (response.code) {
                        401, 403, 404, 410 -> DownloadTransferFailure.SOURCE_UNAVAILABLE
                        408 -> DownloadTransferFailure.TIMEOUT
                        else -> DownloadTransferFailure.NETWORK
                    }
                    throw DownloadTransferException(failure)
                }
                val body = response.body
                val totalBytes = body.contentLength().takeIf { it > 0L }
                val digest = MessageDigest.getInstance("SHA-256")
                var transferred = 0L
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        transferred += count
                        onProgress(transferred, totalBytes)
                    }
                }
                output.flush()
                DownloadTransferResult(
                    bytesTransferred = transferred,
                    reportedContentLength = totalBytes,
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DownloadTransferException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw DownloadTransferException(DownloadTransferFailure.TIMEOUT, error)
        } catch (error: IOException) {
            throw DownloadTransferException(DownloadTransferFailure.NETWORK, error)
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
