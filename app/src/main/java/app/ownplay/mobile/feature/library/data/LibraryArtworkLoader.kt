package app.ownplay.mobile.feature.library.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

internal class LibraryArtworkPayload(
    val cacheKey: String,
    val bytes: ByteArray,
    val contentType: String?,
) {
    init {
        require(cacheKey.isNotBlank()) { "Artwork cache key must not be blank" }
        require(bytes.isNotEmpty()) { "Artwork payload must not be empty" }
    }

    override fun toString(): String =
        "LibraryArtworkPayload(cacheKey=<redacted>, bytes=${bytes.size}, contentType=${contentType ?: "<unspecified>"})"
}

internal interface LibraryArtworkLoader {
    suspend fun load(url: String): LibraryArtworkPayload?
}

internal class OkHttpLibraryArtworkLoader(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val cache: LibraryArtworkMemoryCache = LibraryArtworkMemoryCache(DEFAULT_CACHE_BYTES),
) : LibraryArtworkLoader {
    init {
        require(maxResponseBytes > 0L) { "Artwork response limit must be positive" }
    }

    override suspend fun load(url: String): LibraryArtworkPayload? = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url.trim())
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        val cacheKey = request.url.toString()
        cache.get(cacheKey)?.let { return@withContext it }

        val payload = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                val bytes = readBounded(body, maxResponseBytes) ?: return@use null
                LibraryArtworkPayload(
                    cacheKey = cacheKey,
                    bytes = bytes,
                    contentType = body.contentType()?.toString(),
                )
            }
        } catch (_: IOException) {
            null
        }

        payload?.also(cache::put)
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 5L * 1024L * 1024L
        const val DEFAULT_CACHE_BYTES: Long = 20L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

        private fun readBounded(body: ResponseBody, maxBytes: Long): ByteArray? {
            val declaredLength = body.contentLength()
            if (declaredLength > maxBytes) return null

            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) return null
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray().takeIf { it.isNotEmpty() }
        }
    }
}

internal class LibraryArtworkMemoryCache(
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<String, LibraryArtworkPayload>(16, 0.75f, true)
    private var currentBytes: Long = 0L

    init {
        require(maxBytes > 0L) { "Artwork cache limit must be positive" }
    }

    @Synchronized
    fun get(cacheKey: String): LibraryArtworkPayload? = entries[cacheKey]

    @Synchronized
    fun put(payload: LibraryArtworkPayload) {
        val payloadBytes = payload.bytes.size.toLong()
        if (payloadBytes > maxBytes) return

        entries.remove(payload.cacheKey)?.let { currentBytes -= it.bytes.size.toLong() }
        entries[payload.cacheKey] = payload
        currentBytes += payloadBytes

        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next().value
            currentBytes -= eldest.bytes.size.toLong()
            iterator.remove()
        }
    }

    @Synchronized
    internal fun entryCount(): Int = entries.size

    @Synchronized
    internal fun currentSizeBytes(): Long = currentBytes
}
