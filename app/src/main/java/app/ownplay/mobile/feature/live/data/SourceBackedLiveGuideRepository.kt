package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.live.domain.LiveGuidePolicy
import app.ownplay.mobile.feature.live.domain.LiveGuideRepository
import app.ownplay.mobile.feature.live.domain.LiveNowNext
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.sources.data.m3u.M3uXmltvClient
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

class SourceBackedLiveGuideRepository(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val m3uXmltvClient: M3uXmltvClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LiveGuideRepository {
    private data class CacheEntry(val loadedAtMs: Long, val programs: List<LiveProgram>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val scheduleCache = ConcurrentHashMap<String, CacheEntry>()

    override suspend fun loadNowNext(sourceId: SourceId, channelId: String): LiveNowNext {
        if (channelId.isBlank()) return LiveNowNext()
        val nowMs = nowMillis()
        val cacheKey = "${sourceId.value}:$channelId"
        cache[cacheKey]
            ?.takeIf { nowMs - it.loadedAtMs < CACHE_TTL_MS }
            ?.let { return LiveGuidePolicy.nowNext(it.programs, nowMs / 1_000L) }

        val programs = try {
            loadPrograms(sourceId, channelId, shortOnly = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        cache[cacheKey] = CacheEntry(nowMs, programs)
        return LiveGuidePolicy.nowNext(programs, nowMs / 1_000L)
    }

    override suspend fun loadSchedule(sourceId: SourceId, channelId: String): List<LiveProgram> {
        if (channelId.isBlank()) return emptyList()
        val nowMs = nowMillis()
        val cacheKey = "${sourceId.value}:$channelId"
        scheduleCache[cacheKey]
            ?.takeIf { nowMs - it.loadedAtMs < CACHE_TTL_MS }
            ?.let { return it.programs }

        val programs = try {
            LiveGuidePolicy.normalizeSchedule(
                loadPrograms(sourceId, channelId, shortOnly = false),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        scheduleCache[cacheKey] = CacheEntry(nowMs, programs)
        return programs
    }

    private suspend fun loadPrograms(
        sourceId: SourceId,
        channelId: String,
        shortOnly: Boolean,
    ): List<LiveProgram> {
        val channel = liveOrganizationDao.getAvailableChannel(sourceId.value, channelId)
            ?: return emptyList()
        val source = sourceDao.get(sourceId.value) ?: return emptyList()
        if (!source.enabled) return emptyList()
        val sourceType = runCatching { SourceType.valueOf(source.type) }.getOrNull()
            ?: return emptyList()

        return when (sourceType) {
            SourceType.XTREAM -> {
                val streamId = channel.providerStreamId?.takeIf(String::isNotBlank)
                    ?: return emptyList()
                val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream
                    ?: return emptyList()
                val connection = XtreamConnection(source.baseLocator, secret.username, secret.password)
                val providerPrograms = if (shortOnly) {
                    xtreamClient.shortEpg(connection = connection, streamId = streamId, limit = 4)
                } else {
                    xtreamClient.epgTable(connection = connection, streamId = streamId).ifEmpty {
                        xtreamClient.shortEpg(connection = connection, streamId = streamId, limit = 20)
                    }
                }
                providerPrograms.map { entry ->
                    LiveProgram(entry.title.trim(), entry.startEpochSeconds, entry.endEpochSeconds)
                }
            }

            SourceType.M3U -> {
                val secret = credentialStore.get(sourceId) as? SourceSecret.M3uRemote
                    ?: return emptyList()
                val epgUrl = secret.epgUrl?.trim()?.takeIf(String::isNotBlank)
                    ?: return emptyList()
                val tvgId = channel.tvgId?.trim()?.takeIf(String::isNotBlank)
                    ?: return emptyList()
                m3uXmltvClient.fetch(epgUrl).programsFor(tvgId).map { entry ->
                    LiveProgram(
                        title = entry.title,
                        startEpochSeconds = entry.startEpochSeconds,
                        endEpochSeconds = entry.endEpochSeconds,
                    )
                }
            }
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
    }
}
