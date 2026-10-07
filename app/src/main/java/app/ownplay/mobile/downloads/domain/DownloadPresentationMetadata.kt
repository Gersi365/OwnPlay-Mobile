package app.ownplay.mobile.downloads.domain

data class DownloadEpisodeContext(
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
) {
    init {
        require(seriesId.isNotBlank()) { "Download series id must not be blank" }
        require(seriesTitle.isNotBlank()) { "Download series title must not be blank" }
    }
}

interface DownloadPresentationMetadataResolver {
    suspend fun resolveEpisodeContext(item: DownloadItem): DownloadEpisodeContext?
}
