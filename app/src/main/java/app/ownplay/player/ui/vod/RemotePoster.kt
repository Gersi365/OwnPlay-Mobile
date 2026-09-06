package app.ownplay.player.ui.vod

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.player.source.network.SourceHttpClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val MAX_POSTER_BYTES = 8 * 1024 * 1024
private const val MAX_POSTER_LONG_EDGE_PX = 768
private const val MAX_POSTER_CACHE_ENTRIES = 16

private val posterCacheLock = Any()
private val posterRequestCoordinator = PosterRequestCoordinator<ImageBitmap?>()
private val posterMemoryCache = object : LinkedHashMap<String, ImageBitmap>(
    MAX_POSTER_CACHE_ENTRIES,
    0.75f,
    true,
) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ImageBitmap>?,
    ): Boolean = size > MAX_POSTER_CACHE_ENTRIES
}

private sealed interface RemotePosterState {
    data object Loading : RemotePosterState
    data class Loaded(val image: ImageBitmap) : RemotePosterState
    data object Unavailable : RemotePosterState
}

@Composable
internal fun RemotePoster(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val normalizedUrl = remember(url) {
        url?.trim()?.takeIf(String::isNotBlank)
    }
    val initialState = remember(normalizedUrl) {
        when {
            normalizedUrl == null -> RemotePosterState.Unavailable
            else -> cachedPoster(normalizedUrl)
                ?.let(RemotePosterState::Loaded)
                ?: RemotePosterState.Loading
        }
    }
    val state by produceState<RemotePosterState>(
        initialValue = initialState,
        key1 = normalizedUrl,
    ) {
        val posterUrl = normalizedUrl
        if (posterUrl == null) {
            value = RemotePosterState.Unavailable
            return@produceState
        }

        cachedPoster(posterUrl)?.let { cached ->
            value = RemotePosterState.Loaded(cached)
            return@produceState
        }

        value = RemotePosterState.Loading
        val loadedPoster = posterRequestCoordinator.coalesce(posterUrl) {
            loadRemotePoster(posterUrl)
        }
        value = if (loadedPoster != null) {
            cachePoster(posterUrl, loadedPoster)
            RemotePosterState.Loaded(loadedPoster)
        } else {
            RemotePosterState.Unavailable
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        when (val currentState = state) {
            is RemotePosterState.Loaded -> Image(
                bitmap = currentState.image,
                contentDescription = if (isTelevision) title else null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            RemotePosterState.Loading -> if (isTelevision) {
                PosterFallbackLabel(title = title)
            }
            RemotePosterState.Unavailable -> PosterFallbackLabel(title = title)
        }
    }
}

@Composable
private fun PosterFallbackLabel(title: String) {
    Text(
        text = title.trim().firstOrNull()?.uppercase() ?: "•",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
    )
}

private fun cachedPoster(url: String): ImageBitmap? = synchronized(posterCacheLock) {
    posterMemoryCache[url]
}

private fun cachePoster(url: String, image: ImageBitmap) {
    synchronized(posterCacheLock) {
        posterMemoryCache[url] = image
    }
}

private suspend fun loadRemotePoster(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        SourceHttpClient.shared.newCall(
            Request.Builder().url(url).get().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_POSTER_BYTES.toLong()) return@use null
            val bytes = readPosterBytes(
                input = body.byteStream(),
                maxBytes = MAX_POSTER_BYTES,
            ) ?: return@use null
            decodePoster(bytes)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun decodePoster(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculatePosterInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxLongEdgePx = MAX_POSTER_LONG_EDGE_PX,
        )
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

internal fun calculatePosterInSampleSize(
    width: Int,
    height: Int,
    maxLongEdgePx: Int = MAX_POSTER_LONG_EDGE_PX,
): Int {
    require(maxLongEdgePx > 0) { "maxLongEdgePx must be positive" }
    if (width <= 0 || height <= 0) return 1

    var sampleSize = 1
    while (
        width / sampleSize > maxLongEdgePx ||
        height / sampleSize > maxLongEdgePx
    ) {
        if (sampleSize > Int.MAX_VALUE / 2) break
        sampleSize *= 2
    }
    return sampleSize
}

internal fun readPosterBytes(input: InputStream, maxBytes: Int): ByteArray? {
    if (maxBytes <= 0) return null
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
