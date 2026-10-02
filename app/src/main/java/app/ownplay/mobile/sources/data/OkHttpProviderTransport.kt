package app.ownplay.mobile.sources.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpProviderTransport(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : ProviderTransport {
    override suspend fun get(url: String): ProviderResponse = withContext(Dispatchers.IO) {
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

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body
                val declaredLength = body.contentLength()
                if (declaredLength > maxResponseBytes) {
                    throw ProviderTransportException(
                        ProviderTransportFailureCategory.RESPONSE_TOO_LARGE,
                    )
                }

                ProviderResponse(
                    statusCode = response.code,
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
        } catch (error: ProviderTransportException) {
            throw error
        } catch (error: IOException) {
            throw ProviderTransportException(
                ProviderTransportFailureCategory.NETWORK,
                error,
            )
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
