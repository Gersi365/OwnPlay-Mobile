package app.ownplay.mobile.feature.playback.data

internal object XtreamPlaybackFormatPolicy {
    const val HLS_MIME_TYPE = "application/x-mpegURL"

    fun fallbackExtension(primaryExtension: String): String? = when (primaryExtension.lowercase()) {
        "ts" -> "m3u8"
        "m3u8" -> "ts"
        else -> null
    }

    fun mimeType(extension: String): String? =
        if (extension.equals("m3u8", ignoreCase = true)) HLS_MIME_TYPE else null
}
