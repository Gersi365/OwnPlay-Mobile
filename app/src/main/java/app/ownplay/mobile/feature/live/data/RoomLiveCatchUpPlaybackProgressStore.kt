package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.PlaybackProgressEntity
import app.ownplay.mobile.feature.playback.domain.CatchUpPlaybackProgressStore
import app.ownplay.mobile.feature.playback.domain.LibraryPlaybackProgress
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class RoomLiveCatchUpPlaybackProgressStore(
    private val dao: LibraryDao,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatchUpPlaybackProgressStore {
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
                    // Catch-up progress is best-effort and must not break playback.
                }
            }
        }
    }

    override suspend fun load(target: PlaybackTarget.CatchUp): LibraryPlaybackProgress? {
        val row = try {
            dao.getPlaybackProgress(
                sourceId = target.sourceId.value,
                mediaKind = SourceBackedLiveCatchUpRepository.MEDIA_KIND_CATCH_UP,
                contentId = target.programId,
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
        target: PlaybackTarget.CatchUp,
        progress: LibraryPlaybackProgress,
    ) {
        if (progress.positionMs < 0L || progress.durationMs <= 0L) return
        writes.trySend(
            PlaybackProgressEntity(
                sourceId = target.sourceId.value,
                mediaKind = SourceBackedLiveCatchUpRepository.MEDIA_KIND_CATCH_UP,
                contentId = target.programId,
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
}
