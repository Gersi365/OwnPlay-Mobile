package app.ownplay.mobile.sources.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

class OkHttpProviderTransport(
    client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : ProviderTransport {
    // Provider credentials can be embedded in endpoint paths or queries. Keep same-scheme
    // redirects enabled, but never follow a redirect that changes HTTP/HTTPS.
    private val client = client.newBuilder()
        .followSslRedirects(false)
        .build()

    override suspend fun get(url: String): ProviderResponse {
        val request = try {
            Request.Builder()
                .url(url)
                .get()
                .build()
        } catch (error: IllegalArgumentException) {
            throw ProviderTransportException(
                ProviderTransportFailureCategory.INVALID_REQUEST,
                error,
            )
        }

        return try {
            client.newCall(request).awaitProviderResponse(maxResponseBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ProviderTransportException) {
            throw error
        } catch (error: IOException) {
            throw ProviderTransportException(
                ProviderTransportFailureCategory.NETWORK,
                error,
            )
        }
    }

    private suspend fun Call.awaitProviderResponse(maxResponseBytes: Long): ProviderResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            try {
                enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, error: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val result = try {
                                response.use { openResponse ->
                                    val body = openResponse.body
                                    if (body.contentLength() > maxResponseBytes) {
                                        throw ProviderTransportException(
                                            ProviderTransportFailureCategory.RESPONSE_TOO_LARGE,
                                        )
                                    }

                                    ProviderResponse(
                                        statusCode = openResponse.code,
                                        contentType = body.contentType()?.toString(),
                                        body = body.byteStream().use { input ->
                                            val output = ByteArrayOutputStream()
                                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                            var total = 0L
                                            while (true) {
                                                val read = input.read(buffer)
                                                if (read < 0) break
                                                total += read
                                                if (total > maxResponseBytes) {
                                                    throw ProviderTransportException(
                                                        ProviderTransportFailureCategory.RESPONSE_TOO_LARGE,
                                                    )
                                                }
                                                output.write(buffer, 0, read)
                                            }
                                            output.toString(Charsets.UTF_8.name())
                                        },
                                    )
                                }
                            } catch (error: Throwable) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                                return
                            }

                            if (continuation.isActive) continuation.resume(result)
                        }
                    },
                )
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}
