package app.ownplay.mobile.feature.library.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.feature.library.data.LibraryArtworkDecodePolicy
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.data.LibraryArtworkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ArtworkPresentation {
    POSTER,
    CHANNEL_LOGO,
}

@Composable
internal fun LibraryArtwork(
    url: String?,
    loader: LibraryArtworkLoader,
    compact: Boolean = false,
    presentation: ArtworkPresentation = ArtworkPresentation.POSTER,
    expandPoster: Boolean = false,
    largePoster: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var image by remember(url, loader) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url, loader) {
        image = null
        val artworkUrl = url?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val payload = loader.load(artworkUrl) ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) {
            decodeArtwork(payload)?.asImageBitmap()
        }
    }

    val sizedModifier = when (presentation) {
        ArtworkPresentation.POSTER -> when {
            expandPoster -> modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
            largePoster -> modifier.size(width = 112.dp, height = 168.dp)
            compact -> modifier.size(width = 48.dp, height = 72.dp)
            else -> modifier.size(width = 64.dp, height = 96.dp)
        }
        ArtworkPresentation.CHANNEL_LOGO -> modifier.size(if (compact) 32.dp else 40.dp)
    }
    Surface(
        color = OwnPlayColors.Surface,
        modifier = sizedModifier,
    ) {
        val current = image
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (presentation == ArtworkPresentation.CHANNEL_LOGO) "Logo" else "Artwork",
                    color = OwnPlayColors.TextMuted,
                )
            }
        } else {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun decodeArtwork(payload: LibraryArtworkPayload): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = LibraryArtworkDecodePolicy.sampleSize(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
        )
    }
    return BitmapFactory.decodeByteArray(payload.bytes, 0, payload.bytes.size, options)
}
