package app.ownplay.mobile.feature.library.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.PlaybackProgressEntity
import app.ownplay.mobile.feature.playback.domain.LibraryPlaybackProgress
import app.ownplay.mobile.feature.playback.domain.LibraryPlaybackProgressStore
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class RoomLibraryPlaybackProgressStore(
    private val dao: LibraryDao,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LibraryPlaybackProgressStore {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writes = Channel<PlaybackProgressEntity>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (row in writes) {
                try {
                    dao.upsertPlaybackProgress(row)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Progress persistence is best-effort and must not break playback.
                }
            }
        }
    }

    override suspend fun load(target: PlaybackTarget.Library): LibraryPlaybackProgress? {
        val identity = target.progressIdentity()
        val row = try {
            dao.getPlaybackProgress(
                sourceId = target.sourceId.value,
                mediaKind = identity.mediaKind,
                contentId = identity.contentId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null

        if (row.positionMs < 0L || row.durationMs <= 0L) return null
        return LibraryPlaybackProgress(
            positionMs = row.positionMs,
            durationMs = row.durationMs,
            completed = row.completed,
        )
    }

    override fun record(
        target: PlaybackTarget.Library,
        progress: LibraryPlaybackProgress,
    ) {
        if (progress.positionMs < 0L || progress.durationMs <= 0L) return
        val identity = target.progressIdentity()
        writes.trySend(
            PlaybackProgressEntity(
                sourceId = target.sourceId.value,
                mediaKind = identity.mediaKind,
                contentId = identity.contentId,
                positionMs = progress.positionMs.coerceAtMost(progress.durationMs),
                durationMs = progress.durationMs,
                completed = progress.completed,
                updatedAt = nowEpochMs(),
            ),
        )
    }

    override fun close() {
        writes.close()
    }

    private data class ProgressIdentity(
        val mediaKind: String,
        val contentId: String,
    )

    private fun PlaybackTarget.Library.progressIdentity(): ProgressIdentity = when (this) {
        is PlaybackTarget.Movie -> ProgressIdentity(
            mediaKind = MEDIA_KIND_MOVIE,
            contentId = movieId,
        )
        is PlaybackTarget.Episode -> ProgressIdentity(
            mediaKind = MEDIA_KIND_EPISODE,
            contentId = episodeId,
        )
    }

    private companion object {
        const val MEDIA_KIND_MOVIE = "MOVIE"
        const val MEDIA_KIND_EPISODE = "EPISODE"
    }
}
