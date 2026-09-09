package app.ownplay.player.ui

import app.ownplay.player.download.OfflineDownload
import app.ownplay.player.persistence.download.DownloadMediaKinds
import app.ownplay.player.persistence.download.DownloadStates

internal enum class DownloadsSection(val title: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    NEEDS_ATTENTION("Needs attention"),
    DOWNLOADED("Downloaded"),
}

internal sealed interface DownloadsListItem {
    val key: String

    data class Section(val section: DownloadsSection, val count: Int) : DownloadsListItem {
        override val key = "section:${section.name}"
    }

    data class Group(override val key: String, val title: String, val isSeason: Boolean = false) : DownloadsListItem

    data class Media(val download: OfflineDownload) : DownloadsListItem {
        override val key = "download:${download.downloadId}"
    }
}

/** Presentation only: transfer state and verified offline playback remain repository-owned. */
internal object DownloadsPresentationPolicy {
    private val stableOrder = compareByDescending<OfflineDownload> { it.createdAtEpochMillis }
        .thenBy { it.downloadId }

    fun items(downloads: List<OfflineDownload>): List<DownloadsListItem> = buildList {
        val sections = downloads.groupBy(::section)
        for (section in DownloadsSection.entries) {
            val rows = sections[section]?.sortedWith(stableOrder).orEmpty()
            if (rows.isEmpty()) continue
            add(DownloadsListItem.Section(section, rows.size))
            if (section != DownloadsSection.DOWNLOADED) {
                addAll(rows.map(DownloadsListItem::Media))
                continue
            }

            val movies = rows.filter { it.mediaKind != DownloadMediaKinds.SERIES_EPISODE }
            if (movies.isNotEmpty()) {
                add(DownloadsListItem.Group("group:movies", "Movies"))
                addAll(movies.map(DownloadsListItem::Media))
            }
            // Preserve the newest-created group order. Progress and update timestamps never rank rows.
            val series = rows.filter { it.mediaKind == DownloadMediaKinds.SERIES_EPISODE }
                .groupBy { seriesKey(it) }
            for ((seriesKey, episodes) in series) {
                val title = episodes.first().seriesTitle?.takeIf(String::isNotBlank) ?: "Series episode"
                add(DownloadsListItem.Group("group:series:$seriesKey", title))
                val seasons = episodes.groupBy { it.seasonNumber }
                    .toSortedMap(nullsLast(naturalOrder<Int>()))
                for ((season, seasonEpisodes) in seasons) {
                    add(
                        DownloadsListItem.Group(
                            key = "group:season:$seriesKey:$season",
                            title = season?.let { "Season ${it.toString().padStart(2, '0')}" } ?: "Other episodes",
                            isSeason = true,
                        ),
                    )
                    addAll(
                        seasonEpisodes.sortedWith(
                            compareBy<OfflineDownload, Int?>(nullsLast(naturalOrder())) { it.episodeNumber }
                                .then(stableOrder),
                        ).map(DownloadsListItem::Media),
                    )
                }
            }
        }
    }

    private fun section(download: OfflineDownload): DownloadsSection = when (download.state) {
        DownloadStates.QUEUED, DownloadStates.DOWNLOADING -> DownloadsSection.ACTIVE
        DownloadStates.PAUSED -> DownloadsSection.PAUSED
        DownloadStates.COMPLETED -> DownloadsSection.DOWNLOADED
        else -> DownloadsSection.NEEDS_ATTENTION
    }

    private fun seriesKey(download: OfflineDownload): String {
        // The current download model has no parent series ID. Keep providers separate and do not
        // merge episodes with missing series metadata into an invented series.
        val title = download.seriesTitle?.takeIf(String::isNotBlank)
        val identity = if (title == null) "download:${download.downloadId}" else "title:$title"
        return "${download.sourceId.length}:${download.sourceId}:${identity.length}:$identity"
    }
}
