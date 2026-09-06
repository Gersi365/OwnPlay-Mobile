package app.ownplay.player.ui.library

import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates
import java.io.Serializable
import java.util.Locale

internal data class LibrarySeriesKey(
    val sourceId: String,
    val identity: String,
) : Serializable

internal enum class LibrarySeriesAvailability {
    OFFLINE,
    MANAGED,
}

internal data class LibrarySeriesGroup(
    val key: LibrarySeriesKey,
    val title: String,
    val posterUrl: String?,
    val episodes: List<OfflineDownload>,
) {
    val seriesId: String?
        get() = episodes
            .mapNotNull { seriesIdFromEpisodeContentId(it.contentId) }
            .distinct()
            .singleOrNull()

    val episodeCount: Int
        get() = episodes.size

    val offlineEpisodeCount: Int
        get() = episodes.count { it.state == DownloadStates.COMPLETED }

    val hasOfflineEpisodes: Boolean
        get() = offlineEpisodeCount > 0

    val availability: LibrarySeriesAvailability
        get() = if (hasOfflineEpisodes) {
            LibrarySeriesAvailability.OFFLINE
        } else {
            LibrarySeriesAvailability.MANAGED
        }

    val seasonNumbers: List<Int>
        get() = episodes.mapNotNull { it.seasonNumber }.distinct().sorted()

    val seasonCount: Int
        get() = seasonNumbers.size

    val totalBytesDownloaded: Long
        get() = episodes.sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

    val latestUpdatedAtEpochMillis: Long
        get() = episodes.maxOfOrNull { it.updatedAtEpochMillis } ?: 0L
}

internal fun groupLibrarySeries(downloads: List<OfflineDownload>): List<LibrarySeriesGroup> {
    return downloads
        .asSequence()
        .filter { it.mediaKind == DownloadMediaKinds.SERIES_EPISODE }
        .groupBy(::seriesKey)
        .map { (key, groupedEpisodes) ->
            val episodes = groupedEpisodes.sortedWith(
                compareBy<OfflineDownload>(
                    { it.seasonNumber ?: Int.MAX_VALUE },
                    { it.episodeNumber ?: Int.MAX_VALUE },
                    { it.title.lowercase(Locale.ROOT) },
                ),
            )
            val newestFirst = groupedEpisodes.sortedByDescending { it.updatedAtEpochMillis }
            LibrarySeriesGroup(
                key = key,
                title = newestFirst
                    .asSequence()
                    .mapNotNull { it.seriesTitle?.trim()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: groupedEpisodes.first().title,
                posterUrl = newestFirst
                    .asSequence()
                    .mapNotNull { it.posterUrl?.takeIf(String::isNotBlank) }
                    .firstOrNull(),
                episodes = episodes,
            )
        }
        .sortedWith(
            compareByDescending<LibrarySeriesGroup> { it.latestUpdatedAtEpochMillis }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
}

internal fun seriesIdFromEpisodeContentId(contentId: String): String? {
    val marker = ":episode:"
    val markerIndex = contentId.lastIndexOf(marker)
    if (markerIndex <= 0 || markerIndex + marker.length >= contentId.length) return null
    return contentId.substring(0, markerIndex)
}

private fun seriesKey(download: OfflineDownload): LibrarySeriesKey {
    val exactSeriesId = seriesIdFromEpisodeContentId(download.contentId)
    val seriesTitle = download.seriesTitle?.trim()?.takeIf(String::isNotBlank)
    return LibrarySeriesKey(
        sourceId = download.sourceId,
        identity = exactSeriesId ?: seriesTitle?.lowercase(Locale.ROOT) ?: "episode:${download.contentId}",
    )
}
