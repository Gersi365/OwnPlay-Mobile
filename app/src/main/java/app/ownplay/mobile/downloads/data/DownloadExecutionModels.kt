package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.downloads.domain.DownloadFilePolicy
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.feature.library.data.LibraryPlaybackLocator
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import kotlinx.coroutines.CancellationException

internal data class ResolvedDownloadMedia(
    internal val uri: String,
    val extension: String,
    val displayName: String,
    val relativeDirectories: List<String>,
) {
    init {
        require(uri.isNotBlank()) { "Resolved download URI must not be blank" }
        require(extension.isNotBlank()) { "Resolved download extension must not be blank" }
        require(displayName.isNotBlank()) { "Resolved download display name must not be blank" }
        require(relativeDirectories.none(String::isBlank)) { "Download directories must not be blank" }
    }

    override fun toString(): String =
        "ResolvedDownloadMedia(uri=<redacted>, extension=$extension, displayName=$displayName, directories=${relativeDirectories.size})"
}

internal interface DownloadMediaResolver {
    suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia?
}

internal class SourceBackedDownloadMediaResolver(
    private val libraryDao: LibraryDao,
    private val libraryPlaybackLocator: LibraryPlaybackLocator,
) : DownloadMediaResolver {
    override suspend fun resolve(item: DownloadItem): ResolvedDownloadMedia? = try {
        when (item.mediaKind) {
            DownloadMediaKind.MOVIE -> resolveMovie(item)
            DownloadMediaKind.EPISODE -> resolveEpisode(item)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveMovie(item: DownloadItem): ResolvedDownloadMedia? {
        val movie = libraryDao.getAvailableMovie(item.sourceId.value, item.contentId) ?: return null
        val extension = DownloadFilePolicy.normalizeFiniteExtension(movie.extension) ?: return null
        val prepared = libraryPlaybackLocator.resolve(
            PlaybackTarget.Movie(sourceId = item.sourceId, movieId = item.contentId),
        ) ?: return null
        return ResolvedDownloadMedia(
            uri = prepared.uri,
            extension = extension,
            displayName = DownloadFilePolicy.fileName(movie.name, extension, item.downloadId.value),
            relativeDirectories = listOf("Movies"),
        )
    }

    private suspend fun resolveEpisode(item: DownloadItem): ResolvedDownloadMedia? {
        val episode = libraryDao.getAvailableEpisode(item.sourceId.value, item.contentId) ?: return null
        val series = libraryDao.getAvailableSeries(item.sourceId.value, episode.seriesId) ?: return null
        val extension = DownloadFilePolicy.normalizeFiniteExtension(episode.extension) ?: return null
        val prepared = libraryPlaybackLocator.resolve(
            PlaybackTarget.Episode(sourceId = item.sourceId, episodeId = item.contentId),
        ) ?: return null
        return ResolvedDownloadMedia(
            uri = prepared.uri,
            extension = extension,
            displayName = DownloadFilePolicy.episodeFileName(
                title = episode.title,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                extension = extension,
                identitySuffix = item.downloadId.value,
            ),
            relativeDirectories = listOf(
                "Series",
                DownloadFilePolicy.safeSegment(series.name, "Series"),
                DownloadFilePolicy.seasonDirectory(episode.seasonNumber),
            ),
        )
    }
}
