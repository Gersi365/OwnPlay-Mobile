package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.live.domain.LiveCatchUpCatalog
import app.ownplay.mobile.feature.live.domain.LiveCatchUpPolicy
import app.ownplay.mobile.feature.live.domain.LiveCatchUpProgram
import app.ownplay.mobile.feature.live.domain.LiveCatchUpRepository
import app.ownplay.mobile.feature.live.domain.LiveGuideRepository
import app.ownplay.mobile.sources.data.m3u.M3uCatchUpResolver
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import kotlinx.coroutines.CancellationException

class SourceBackedLiveCatchUpRepository internal constructor(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val libraryDao: LibraryDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val liveGuideRepository: LiveGuideRepository,
    private val m3uCatchUpResolver: M3uCatchUpResolver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LiveCatchUpRepository {
    override suspend fun loadAvailableChannelIds(sourceId: SourceId): Set<String> {
        return try {
            val source = sourceDao.get(sourceId.value) ?: return emptySet()
            if (!source.enabled) return emptySet()
            val channels = liveOrganizationDao.getAvailableLiveChannelsForManagement(sourceId.value)
            when (runCatching { SourceType.valueOf(source.type) }.getOrNull()) {
                SourceType.XTREAM -> {
                    val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream
                        ?: return emptySet()
                    val streams = xtreamClient.liveStreams(
                        XtreamConnection(source.baseLocator, secret.username, secret.password),
                    )
                    val supportedStreamIds = streams
                        .asSequence()
                        .filter { it.catchUpAvailable }
                        .map { it.streamId }
                        .toSet()
                    channels.asSequence()
                        .filter { it.providerStreamId in supportedStreamIds }
                        .map { it.channelId }
                        .toSet()
                }
                SourceType.M3U -> {
                    val secret = credentialStore.get(sourceId) as? SourceSecret.M3uRemote
                        ?: return emptySet()
                    channels.mapNotNull { channel ->
                        val entry = m3uCatchUpResolver.resolveEntry(
                            secret = secret,
                            tvgId = channel.tvgId,
                            streamLocator = channel.streamLocator,
                        )
                        channel.channelId.takeIf { entry != null && m3uCatchUpResolver.supports(entry) }
                    }.toSet()
                }
                null -> emptySet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
    }

    override suspend fun loadCatalog(
        sourceId: SourceId,
        channelId: String,
    ): LiveCatchUpCatalog {
        if (channelId.isBlank()) return unsupported()

        return try {
            val channel = liveOrganizationDao.getAvailableChannel(sourceId.value, channelId)
                ?: return unsupported()
            val source = sourceDao.get(sourceId.value) ?: return unsupported()
            if (!source.enabled) return unsupported()
            when (runCatching { SourceType.valueOf(source.type) }.getOrNull()) {
                SourceType.XTREAM -> loadXtreamCatalog(sourceId, channelId, channel, source.baseLocator)
                SourceType.M3U -> loadM3uCatalog(sourceId, channelId, channel)
                null -> unsupported()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unsupported()
        }
    }

    private suspend fun loadXtreamCatalog(
        sourceId: SourceId,
        channelId: String,
        channel: LiveChannelEntity,
        baseLocator: String,
    ): LiveCatchUpCatalog {
        val streamId = channel.providerStreamId?.takeIf(String::isNotBlank) ?: return unsupported()
        val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream ?: return unsupported()
        val connection = XtreamConnection(baseLocator, secret.username, secret.password)
        val providerStream = xtreamClient.liveStreams(connection)
            .firstOrNull { stream -> stream.streamId == streamId }
            ?: return unsupported()
        if (!providerStream.catchUpAvailable) return unsupported()

        val programs = LiveCatchUpPolicy.eligiblePrograms(
            channelId = channelId,
            programs = liveGuideRepository.loadSchedule(sourceId, channelId),
            nowEpochSeconds = nowMillis() / 1_000L,
            archiveDurationDays = providerStream.catchUpDurationDays,
        )
        return catalogWithProgress(
            sourceId = sourceId,
            programs = programs,
            downloadableProgramIds = programs.map(LiveCatchUpProgram::programId).toSet(),
        )
    }

    private suspend fun loadM3uCatalog(
        sourceId: SourceId,
        channelId: String,
        channel: LiveChannelEntity,
    ): LiveCatchUpCatalog {
        val secret = credentialStore.get(sourceId) as? SourceSecret.M3uRemote ?: return unsupported()
        val epgUrl = secret.epgUrl?.trim()?.takeIf(String::isNotBlank) ?: return unsupported()
        val tvgId = channel.tvgId?.trim()?.takeIf(String::isNotBlank) ?: return unsupported()
        val entry = m3uCatchUpResolver.resolveEntry(
            secret = secret,
            tvgId = tvgId,
            streamLocator = channel.streamLocator,
        ) ?: return unsupported()
        if (!m3uCatchUpResolver.supports(entry)) return unsupported()

        val programs = LiveCatchUpPolicy.eligiblePrograms(
            channelId = channelId,
            programs = liveGuideRepository.loadSchedule(sourceId, channelId),
            nowEpochSeconds = nowMillis() / 1_000L,
            archiveDurationDays = entry.catchUpDays,
        )
        val downloadableProgramIds = programs.asSequence()
            .filter { program ->
                m3uCatchUpResolver.downloadableExtension(
                    entry = entry,
                    startEpochSeconds = program.startEpochSeconds,
                    endEpochSeconds = program.endEpochSeconds,
                ) != null
            }
            .map(LiveCatchUpProgram::programId)
            .toSet()
        return catalogWithProgress(sourceId, programs, downloadableProgramIds)
    }

    private suspend fun catalogWithProgress(
        sourceId: SourceId,
        programs: List<LiveCatchUpProgram>,
        downloadableProgramIds: Set<String> = emptySet(),
    ): LiveCatchUpCatalog {
        val progressById = libraryDao
            .getPlaybackProgressForKind(sourceId.value, MEDIA_KIND_CATCH_UP)
            .associateBy { row -> row.contentId }
        return LiveCatchUpCatalog(
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
            downloadableProgramIds = downloadableProgramIds,
        )
    }

    private fun unsupported(): LiveCatchUpCatalog = LiveCatchUpCatalog(supported = false)

    companion object {
        const val MEDIA_KIND_CATCH_UP = "CATCH_UP"
    }
}
