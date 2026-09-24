package app.ownplay.mobile.feature.library.ui

import app.ownplay.mobile.feature.library.domain.LibraryMovieDetailMetadata

internal object LibraryMovieDetailPresentation {
    fun runtimeLabel(runtimeMs: Long?): String? {
        val totalMinutes = runtimeMs
            ?.takeIf { it > 0L }
            ?.div(60_000L)
            ?.takeIf { it > 0L }
            ?: return null
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun metadataLine(metadata: LibraryMovieDetailMetadata): String? =
        listOfNotNull(
            metadata.year?.takeIf(String::isNotBlank),
            runtimeLabel(metadata.runtimeMs),
            metadata.rating?.takeIf(String::isNotBlank)?.let { "★ $it" },
        ).joinToString(" • ").takeIf(String::isNotBlank)
}
