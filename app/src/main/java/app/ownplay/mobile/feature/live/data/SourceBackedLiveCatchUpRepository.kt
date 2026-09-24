package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.live.domain.LiveCatchUpCatalog
import app.ownplay.mobile.feature.live.domain.LiveCatchUpPolicy
import app.ownplay.mobile.feature.live.domain.LiveCatchUpRepository
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.CancellationException

class SourceBackedLiveCatchUpRepository(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val libraryDao: LibraryDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LiveCatchUpRepository {
    override suspend fun loadCatalog(
        sourceId: SourceId,
        channelId: String,
    ): LiveCatchUpCatalog {
        if (channelId.isBlank()) return LiveCatchUpCatalog(supported = false)

        return try {
            val channel = liveOrganizationDao.getAvailableChannel(sourceId.value, channelId)
                ?: return LiveCatchUpCatalog(supported = false)
            val source = sourceDao.get(sourceId.value)
                ?: return LiveCatchUpCatalog(supported = false)
            if (!source.enabled || source.type != SourceType.XTREAM.name) {
                return LiveCatchUpCatalog(supported = false)
            }
            val streamId = channel.providerStreamId?.takeIf(String::isNotBlank)
                ?: return LiveCatchUpCatalog(supported = false)
            val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream
                ?: return LiveCatchUpCatalog(supported = false)
            val connection = XtreamConnection(source.baseLocator, secret.username, secret.password)

            val providerStream = xtreamClient.liveStreams(connection)
                .firstOrNull { stream -> stream.streamId == streamId }
                ?: return LiveCatchUpCatalog(supported = false)
            if (!providerStream.catchUpAvailable) {
                return LiveCatchUpCatalog(supported = false)
            }

            val nowEpochSeconds = nowMillis() / 1_000L
            val programs = LiveCatchUpPolicy.eligiblePrograms(
                channelId = channelId,
                programs = xtreamClient.epgTable(connection, streamId).map { entry ->
                    LiveProgram(
                        title = entry.title,
                        startEpochSeconds = entry.startEpochSeconds,
                        endEpochSeconds = entry.endEpochSeconds,
                    )
                },
                nowEpochSeconds = nowEpochSeconds,
                archiveDurationDays = providerStream.catchUpDurationDays,
            )

            val progressById = libraryDao
                .getPlaybackProgressForKind(sourceId.value, MEDIA_KIND_CATCH_UP)
                .associateBy { row -> row.contentId }
            LiveCatchUpCatalog(
                supported = true,
                programs = programs.map { program ->
                    val progress = progressById[program.programId] ?: return@map program
                    LiveCatchUpPolicy.withProgress(
                        program = program,
                        positionMs = progress.positionMs,
                        durationMs = progress.durationMs,
                        completed = progress.completed,
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LiveCatchUpCatalog(supported = false)
        }
    }

    companion object {
        const val MEDIA_KIND_CATCH_UP = "CATCH_UP"
    }
}
