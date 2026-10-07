package app.ownplay.mobile.feature.playback.data

import app.ownplay.mobile.feature.playback.domain.LivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSource
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackAlternative
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import java.net.URI
import app.ownplay.mobile.sources.data.xtream.XtreamLiveStreamIdentity
import app.ownplay.mobile.sources.data.xtream.XtreamUrlBuilder
import app.ownplay.mobile.sources.domain.ConnectionValidation
import app.ownplay.mobile.sources.domain.SourceConnectionSecurityPolicy

internal class DefaultLivePlaybackMediaPreparer : LivePlaybackMediaPreparer {
    override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia? = when (source) {
        is LivePlaybackSource.Direct -> prepareDirect(source)
        is LivePlaybackSource.Xtream -> prepareXtream(source)
    }

    private fun prepareDirect(source: LivePlaybackSource.Direct): PreparedPlaybackMedia? {
        val normalized = when (
            val validation = SourceConnectionSecurityPolicy.normalizeRemoteMediaUrl(source.streamLocator)
        ) {
            is ConnectionValidation.Valid -> validation.normalizedUrl
            is ConnectionValidation.Invalid -> return null
        }

        val mimeType = inferMimeType(normalized)
        return PreparedPlaybackMedia(
            uri = normalized,
            mimeType = mimeType,
            fallback = if (mimeType == null && isOpaqueNetworkUri(normalized)) {
                PreparedPlaybackAlternative(
                    uri = normalized,
                    mimeType = HLS_MIME_TYPE,
                )
            } else {
                null
            },
        )
    }

    private fun prepareXtream(source: LivePlaybackSource.Xtream): PreparedPlaybackMedia? {
        val providerExtension = XtreamLiveStreamIdentity.extension(
            identity = source.opaqueStreamIdentity,
            streamId = source.streamId,
        )
        val primaryExtension = providerExtension ?: DEFAULT_XTREAM_LIVE_EXTENSION
        val fallbackExtension = XtreamPlaybackFormatPolicy.fallbackExtension(primaryExtension)

        fun buildUri(extension: String): String? = runCatching {
            XtreamUrlBuilder.liveStream(
                baseUrl = source.baseUrl,
                username = source.username,
                password = source.password,
                streamId = source.streamId,
                extension = extension,
            )
        }.getOrNull()

        val uri = buildUri(primaryExtension) ?: return null
        val fallback = fallbackExtension
            ?.let(::buildUri)
            ?.let { fallbackUri ->
                PreparedPlaybackAlternative(
                    uri = fallbackUri,
                    mimeType = XtreamPlaybackFormatPolicy.mimeType(fallbackExtension),
                )
            }

        return PreparedPlaybackMedia(
            uri = uri,
            mimeType = XtreamPlaybackFormatPolicy.mimeType(primaryExtension),
            fallback = fallback,
        )
    }

    private fun inferMimeType(uri: String): String? =
        if (uri.substringBefore('?').lowercase().endsWith(".m3u8")) HLS_MIME_TYPE else null

    private fun isOpaqueNetworkUri(uri: String): Boolean = runCatching {
        val parsed = URI(uri)
        if (!parsed.scheme.equals("http", true) && !parsed.scheme.equals("https", true)) {
            return@runCatching false
        }
        val segment = parsed.path.orEmpty().trimEnd('/').substringAfterLast('/')
        segment.isBlank() || '.' !in segment
    }.getOrDefault(false)

    private companion object {
        const val HLS_MIME_TYPE = "application/x-mpegURL"
        const val DEFAULT_XTREAM_LIVE_EXTENSION = "ts"
    }
}
