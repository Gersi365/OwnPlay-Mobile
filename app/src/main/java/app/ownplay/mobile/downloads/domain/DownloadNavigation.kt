package app.ownplay.mobile.downloads.domain

import app.ownplay.mobile.sources.domain.SourceId

data class DownloadDetailsNavigation(
    val downloadId: DownloadId,
    val sourceId: SourceId,
    val mediaKind: DownloadMediaKind,
    val contentId: String,
) {
    init {
        require(contentId.isNotBlank()) { "Download navigation content id must not be blank" }
    }
}
