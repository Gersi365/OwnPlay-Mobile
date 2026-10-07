package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.downloads.domain.DownloadEpisodeContext
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadPresentationMetadataResolver

internal class RoomDownloadPresentationMetadataResolver(
    private val libraryDao: LibraryDao,
) : DownloadPresentationMetadataResolver {
    override suspend fun resolveEpisodeContext(item: DownloadItem): DownloadEpisodeContext? {
        if (item.mediaKind != DownloadMediaKind.EPISODE) return null
        val row = libraryDao.getDownloadEpisodeMetadata(item.sourceId.value, item.contentId) ?: return null
        return DownloadEpisodeContext(
            seriesId = row.seriesId,
            seriesTitle = row.seriesTitle,
            seasonNumber = row.seasonNumber,
            episodeNumber = row.episodeNumber,
        )
    }
}
