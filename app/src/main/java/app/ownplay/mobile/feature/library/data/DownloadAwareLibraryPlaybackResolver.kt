package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.data.DownloadedMediaVerifier
import app.ownplay.mobile.downloads.data.DownloadIdentity
import app.ownplay.mobile.downloads.domain.DownloadFailureCode
import app.ownplay.mobile.feature.playback.domain.LibraryPlaybackMediaResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import kotlinx.coroutines.CancellationException

internal class DownloadAwareLibraryPlaybackResolver(
    private val onlineResolver: LibraryPlaybackMediaResolver,
    private val downloadRepository: DownloadRepository,
    private val verifier: DownloadedMediaVerifier,
) : LibraryPlaybackMediaResolver {
    override suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia? {
        val explicitOffline = target.offlineDownloadId != null
        val expectedKind = when (target) {
            is PlaybackTarget.Movie -> DownloadMediaKind.MOVIE
            is PlaybackTarget.Episode -> DownloadMediaKind.EPISODE
        }
        val expectedContentId = when (target) {
            is PlaybackTarget.Movie -> target.movieId
            is PlaybackTarget.Episode -> target.episodeId
        }
        val candidateId = target.offlineDownloadId
            ?.let(::DownloadId)
            ?: DownloadIdentity.stableId(target.sourceId, expectedKind, expectedContentId)

        return try {
            val item = downloadRepository.get(candidateId)
            if (item == null) {
                return if (explicitOffline) null else onlineResolver.resolve(target)
            }
            if (
                item.sourceId != target.sourceId ||
                item.mediaKind != expectedKind ||
                item.contentId != expectedContentId
            ) {
                return if (explicitOffline) null else onlineResolver.resolve(target)
            }
            if (item.status != DownloadStatus.COMPLETED) {
                return if (explicitOffline) null else onlineResolver.resolve(target)
            }

            val reference = item.localReference?.takeIf(String::isNotBlank)
            val metadataConsistent =
                reference != null &&
                    item.verifiedBytes != null &&
                    item.verifiedBytes == item.bytesDownloaded &&
                    (item.totalBytes == null || item.totalBytes == item.verifiedBytes)
            val verified = if (metadataConsistent) {
                try {
                    verifier.verify(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            if (!verified) {
                downloadRepository.fail(item.downloadId, DownloadFailureCode.INTEGRITY)
                return if (explicitOffline) null else onlineResolver.resolve(target)
            }

            // Verification can take time. A removed or replaced record must not be played.
            if (downloadRepository.get(item.downloadId) != item) {
                return if (explicitOffline) null else onlineResolver.resolve(target)
            }
            PreparedPlaybackMedia(uri = requireNotNull(reference))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (explicitOffline) null else onlineResolver.resolve(target)
        }
    }
}
