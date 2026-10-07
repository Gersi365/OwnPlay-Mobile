package app.ownplay.mobile.downloads.data

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    suspend fun transferFinite(
        uri: String,
        output: OutputStream,
        onProgress: suspend (bytesTransferred: Long, totalBytes: Long?) -> Unit,
    ): DownloadTransferResult = transfer(uri, output, onProgress)
}

internal class OkHttpDownloadTransferClient(
    client: OkHttpClient = defaultClient(),
) : DownloadTransferClient {
    private val client = client.withCrossSchemeRedirectsDisabled()
    override suspend fun transfer(
        uri: String,
        output: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadTransferResult = transferInternal(uri, output, onProgress, requireContentLength = false)

    override suspend fun transferFinite(
        uri: String,
        output: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadTransferResult = transferInternal(uri, output, onProgress, requireContentLength = true)

    private suspend fun transferInternal(
        uri: String,
        output: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit,
        requireContentLength: Boolean,
    ): DownloadTransferResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(uri).get().build()
        } catch (error: IllegalArgumentException) {
            throw DownloadTransferException(DownloadTransferFailure.SOURCE_UNAVAILABLE, error)
        }

        val call = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
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
                if (requireContentLength && totalBytes == null) {
                    throw DownloadTransferException(DownloadTransferFailure.SOURCE_UNAVAILABLE)
                }
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
            currentCoroutineContext().ensureActive()
            throw DownloadTransferException(DownloadTransferFailure.NETWORK, error)
        } finally {
            cancellation?.dispose()
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
