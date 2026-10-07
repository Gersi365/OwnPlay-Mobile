package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveGuideDao
import app.ownplay.mobile.data.db.LiveGuideProgramEntity
import app.ownplay.mobile.data.db.LiveGuideSnapshotEntity
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.feature.live.domain.LiveGuidePolicy
import app.ownplay.mobile.feature.live.domain.LiveGuideRepository
import app.ownplay.mobile.feature.live.domain.LiveNowNext
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.sources.data.m3u.M3uXmltvClient
import app.ownplay.mobile.sources.data.ProviderTransportException
import app.ownplay.mobile.sources.data.ProviderTransportFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamClientException
import app.ownplay.mobile.sources.data.xtream.XtreamClientFailureCategory
import app.ownplay.mobile.sources.data.xtream.XtreamConnection
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceBackedLiveGuideRepository(
    private val sourceDao: SourceDao,
    private val liveOrganizationDao: LiveOrganizationDao,
    private val liveGuideDao: LiveGuideDao,
    private val credentialStore: CredentialStore,
    private val xtreamClient: XtreamClient,
    private val m3uXmltvClient: M3uXmltvClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LiveGuideRepository {
    // Only in-flight work is retained in memory. Successful guide data lives in Room.
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    override suspend fun loadNowNext(sourceId: SourceId, channelId: String): LiveNowNext {
        if (channelId.isBlank()) return LiveNowNext()
        val now = nowMillis()
        val cached = LiveGuideCachePolicy.selectForNowNext(
            candidates = listOf(
                readCached(sourceId, channelId, SNAPSHOT_NOW_NEXT),
                readCached(sourceId, channelId, SNAPSHOT_FULL),
            ).filterNotNull(),
            nowEpochSeconds = now / 1_000L,
        )

        if (cached != null) {
            if (now - cached.fetchedAtMs >= CACHE_TTL_MS) {
                refreshInBackground(sourceId, channelId, SNAPSHOT_NOW_NEXT, shortOnly = true)
            }
            return LiveGuidePolicy.nowNext(cached.programs, now / 1_000L)
        }

        val programs = loadOrRefresh(
            sourceId = sourceId,
            channelId = channelId,
            snapshotKind = SNAPSHOT_NOW_NEXT,
            shortOnly = true,
        )
        return LiveGuidePolicy.nowNext(programs, nowMillis() / 1_000L)
    }

    override suspend fun loadSchedule(sourceId: SourceId, channelId: String): List<LiveProgram> {
        if (channelId.isBlank()) return emptyList()
        val cached = readCached(sourceId, channelId, SNAPSHOT_FULL)
        if (cached != null) {
            if (nowMillis() - cached.fetchedAtMs >= CACHE_TTL_MS) {
                refreshInBackground(sourceId, channelId, SNAPSHOT_FULL, shortOnly = false)
            }
            return cached.programs
        }
        return loadOrRefresh(
            sourceId = sourceId,
            channelId = channelId,
            snapshotKind = SNAPSHOT_FULL,
            shortOnly = false,
        )
    }

    private suspend fun loadOrRefresh(
        sourceId: SourceId,
        channelId: String,
        snapshotKind: String,
        shortOnly: Boolean,
    ): List<LiveProgram> {
        return try {
            fetchAndStoreSingleFlight(sourceId, channelId, snapshotKind, shortOnly)?.programs.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readCached(sourceId, channelId, snapshotKind)?.programs.orEmpty()
        }
    }

    private fun refreshInBackground(
        sourceId: SourceId,
        channelId: String,
        snapshotKind: String,
        shortOnly: Boolean,
    ) {
        val key = inFlightKey(sourceId, channelId, snapshotKind)
        val signal = CompletableDeferred<Unit>()
        if (inFlight.putIfAbsent(key, signal) != null) return

        refreshScope.launch {
            try {
                fetchAndStore(sourceId, channelId, snapshotKind, shortOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last successful Room snapshot when the provider is unavailable.
            } finally {
                signal.complete(Unit)
                inFlight.remove(key, signal)
            }
        }
    }

    private suspend fun fetchAndStoreSingleFlight(
        sourceId: SourceId,
        channelId: String,
        snapshotKind: String,
        shortOnly: Boolean,
    ): LiveGuideCacheCandidate? {
        val key = inFlightKey(sourceId, channelId, snapshotKind)
        inFlight[key]?.let {
            it.await()
            return readCached(sourceId, channelId, snapshotKind)
        }

        val signal = CompletableDeferred<Unit>()
        val existing = inFlight.putIfAbsent(key, signal)
        if (existing != null) {
            existing.await()
            return readCached(sourceId, channelId, snapshotKind)
        }

        try {
            return fetchAndStore(sourceId, channelId, snapshotKind, shortOnly)
        } finally {
            signal.complete(Unit)
            inFlight.remove(key, signal)
        }
    }

    private suspend fun fetchAndStore(
        sourceId: SourceId,
        channelId: String,
        snapshotKind: String,
        shortOnly: Boolean,
    ): LiveGuideCacheCandidate {
        val loaded = loadPrograms(sourceId, channelId, shortOnly)
            ?: throw IllegalStateException("Live guide source or channel is unavailable")
        val normalized = withContext(Dispatchers.Default) {
            LiveGuidePolicy.normalizeSchedule(
                loaded,
            ).let { programs ->
                if (snapshotKind == SNAPSHOT_NOW_NEXT) programs.take(NOW_NEXT_CACHE_LIMIT) else programs
            }
        }
        val previous = readCached(sourceId, channelId, snapshotKind)
        if (previous != null && LiveGuideCachePolicy.shouldPreserveLastGood(previous.programs, normalized)) {
            return previous
        }

        val fetchedAt = nowMillis()
        val snapshot = LiveGuideSnapshotEntity(
            sourceId = sourceId.value,
            channelId = channelId,
            snapshotKind = snapshotKind,
            fetchedAtEpochMs = fetchedAt,
            isEmpty = normalized.isEmpty(),
        )
        val rows = normalized.mapIndexed { index, program ->
            LiveGuideProgramEntity(
                sourceId = sourceId.value,
                channelId = channelId,
                snapshotKind = snapshotKind,
                programKey = programKey(program),
                title = program.title,
                startEpochSeconds = program.startEpochSeconds,
                endEpochSeconds = program.endEpochSeconds,
                providerOrder = index,
            )
        }
        liveGuideDao.replaceSnapshot(snapshot, rows)
        return LiveGuideCacheCandidate(fetchedAt, normalized)
    }

    private suspend fun readCached(
        sourceId: SourceId,
        channelId: String,
        snapshotKind: String,
    ): LiveGuideCacheCandidate? {
        val snapshot = liveGuideDao.getSnapshot(sourceId.value, channelId, snapshotKind) ?: return null
        val rows = liveGuideDao.getPrograms(sourceId.value, channelId, snapshotKind)
        val programs = withContext(Dispatchers.Default) {
            LiveGuidePolicy.normalizeSchedule(
                rows.map { row ->
                    LiveProgram(
                        title = row.title,
                        startEpochSeconds = row.startEpochSeconds,
                        endEpochSeconds = row.endEpochSeconds,
                    )
                },
            )
        }
        // `isEmpty` records a successful empty response; no snapshot row means failure/no cache.
        return LiveGuideCacheCandidate(snapshot.fetchedAtEpochMs, if (snapshot.isEmpty) emptyList() else programs)
    }

    private suspend fun loadPrograms(
        sourceId: SourceId,
        channelId: String,
        shortOnly: Boolean,
    ): List<LiveProgram>? {
        val channel = liveOrganizationDao.getAvailableChannel(sourceId.value, channelId)
            ?: return null
        val source = sourceDao.get(sourceId.value) ?: return null
        if (!source.enabled) return null
        val sourceType = runCatching { SourceType.valueOf(source.type) }.getOrNull()
            ?: return null

        return when (sourceType) {
            SourceType.XTREAM -> loadXtreamPrograms(sourceId, channel, source.baseLocator, shortOnly)
            SourceType.M3U -> loadM3uPrograms(sourceId, channel, shortOnly)
        }
    }

    private suspend fun loadXtreamPrograms(
        sourceId: SourceId,
        channel: LiveChannelEntity,
        baseLocator: String,
        shortOnly: Boolean,
    ): List<LiveProgram>? {
        val streamId = channel.providerStreamId?.takeIf(String::isNotBlank) ?: return null
        val secret = credentialStore.get(sourceId) as? SourceSecret.Xtream ?: return null
        val connection = XtreamConnection(baseLocator, secret.username, secret.password)
        val programs = if (shortOnly) {
            xtreamClient.shortEpg(connection = connection, streamId = streamId, limit = 4)
        } else {
            try {
                xtreamClient.epgTable(connection = connection, streamId = streamId)
            } catch (error: XtreamClientException) {
                if (error.category !in FALLBACK_ELIGIBLE_XTREAM_ERRORS) throw error
                xtreamClient.shortEpg(connection = connection, streamId = streamId, limit = 20)
            } catch (error: ProviderTransportException) {
                if (error.category !in FALLBACK_ELIGIBLE_TRANSPORT_ERRORS) throw error
                xtreamClient.shortEpg(connection = connection, streamId = streamId, limit = 20)
            }
        }
        return programs.map { entry ->
            LiveProgram(entry.title, entry.startEpochSeconds, entry.endEpochSeconds)
        }
    }

    private suspend fun loadM3uPrograms(
        sourceId: SourceId,
        channel: LiveChannelEntity,
        shortOnly: Boolean,
    ): List<LiveProgram>? {
        val secret = credentialStore.get(sourceId) as? SourceSecret.M3uRemote ?: return null
        val epgUrl = secret.epgUrl?.trim()?.takeIf(String::isNotBlank) ?: return null
        val tvgId = channel.tvgId?.trim()?.takeIf(String::isNotBlank) ?: return null
        val programs = m3uXmltvClient.fetchPrograms(epgUrl, tvgId)
            .map { entry -> LiveProgram(entry.title, entry.startEpochSeconds, entry.endEpochSeconds) }
        return if (shortOnly) programs.take(NOW_NEXT_CACHE_LIMIT) else programs
    }

    private fun inFlightKey(sourceId: SourceId, channelId: String, snapshotKind: String) =
        "${sourceId.value}:$channelId:$snapshotKind"

    private fun programKey(program: LiveProgram): String {
        val identity = listOf(
            program.title,
            program.startEpochSeconds?.toString().orEmpty(),
            program.endEpochSeconds?.toString().orEmpty(),
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private companion object {
        const val SNAPSHOT_NOW_NEXT = "NOW_NEXT"
        const val SNAPSHOT_FULL = "FULL"
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
        const val NOW_NEXT_CACHE_LIMIT = 20
        val FALLBACK_ELIGIBLE_XTREAM_ERRORS = setOf(
            XtreamClientFailureCategory.PROVIDER,
            XtreamClientFailureCategory.TRANSIENT_PROVIDER,
        )
        val FALLBACK_ELIGIBLE_TRANSPORT_ERRORS = setOf(
            ProviderTransportFailureCategory.NETWORK,
            ProviderTransportFailureCategory.RESPONSE_TOO_LARGE,
        )
    }
}
