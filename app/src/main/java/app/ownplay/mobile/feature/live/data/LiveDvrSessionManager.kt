package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.feature.playback.domain.LiveDvrReadHandle
import app.ownplay.mobile.feature.playback.domain.LiveDvrSessionGateway
import app.ownplay.mobile.feature.playback.domain.LiveDvrTimelineSnapshot
import app.ownplay.mobile.feature.playback.domain.LivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.domain.LivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackAlternative
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import app.ownplay.mobile.sources.domain.SourceId
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class LiveDvrIngressSegment(
    val identity: String,
    val durationMs: Long,
    val programTimeEpochMs: Long?,
    val discontinuitySequence: Long,
)

internal interface LiveDvrSegmentBoundaryOutput {
    fun beginSegment(segment: LiveDvrIngressSegment): Boolean
    fun endSegment(complete: Boolean)
}

internal interface LiveDvrIngressClient {
    suspend fun capture(
        uri: String,
        isHls: Boolean,
        startEpochMillis: Long,
        endEpochMillis: Long,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit = {},
    )
}

internal enum class LiveDvrSessionState {
    STARTING,
    ACTIVE,
    RECONNECTING,
    FINALIZING,
    TERMINAL,
    FAILED,
}

internal data class LiveDvrTimelineEntry(
    val identity: String?,
    val startOffset: Long,
    val endOffset: Long,
    val startEpochMillis: Long,
    val durationMillis: Long,
    val discontinuitySequence: Long,
) {
    val endEpochMillis: Long
        get() = (startEpochMillis + durationMillis).coerceAtLeast(startEpochMillis)
}

internal data class LiveDvrRetainedPoint(
    val byteOffset: Long,
    val epochMillis: Long?,
)

internal data class LiveDvrStorageSpace(
    val usableBytes: Long,
    val totalBytes: Long,
)

internal fun interface LiveDvrStorageMonitor {
    fun space(directory: File): LiveDvrStorageSpace
}

/** Reserve 5% of the volume, with a 256 MiB minimum, for non-DVR app/system writes. */
internal object LiveDvrStoragePolicy {
    const val MIN_HEADROOM_BYTES: Long = 256L * 1024L * 1024L

    fun requiredHeadroom(totalBytes: Long): Long =
        maxOf(MIN_HEADROOM_BYTES, totalBytes.coerceAtLeast(0L) / 20L)

    fun canStart(space: LiveDvrStorageSpace): Boolean =
        space.usableBytes >= requiredHeadroom(space.totalBytes)

    fun canWrite(space: LiveDvrStorageSpace, incomingBytes: Long, recordingOutputCount: Int): Boolean {
        if (incomingBytes < 0L || recordingOutputCount < 0) return false
        val copies = 1L + recordingOutputCount
        val projectedWrite = runCatching { Math.multiplyExact(incomingBytes, copies) }.getOrNull()
            ?: return false
        if (space.usableBytes < projectedWrite) return false
        return space.usableBytes - projectedWrite >= requiredHeadroom(space.totalBytes)
    }
}

internal data class LiveDvrSessionSnapshot(
    val sessionId: String,
    val generation: Long,
    val state: LiveDvrSessionState,
    val providerIngressOpenCount: Int,
    val playbackAttached: Boolean,
    val recordingIds: Set<String>,
    val capacityLeaseId: String,
    val retainedBytes: Long,
    val activeReaderCount: Int,
    val timeline: List<LiveDvrTimelineEntry>,
    val failure: String?,
    val failureCategory: LiveRecordingFailureCategory?,
)

/**
 * Owns one provider ingress for each active Live source/channel. Media3 and recording receive
 * bytes from the same append-only retained store; a recording pin only tees bytes already
 * entering that store and never opens a provider client of its own.
 */
internal class LiveDvrSessionManager(
    private val sessionDirectory: File,
    private val sourceResolver: LivePlaybackSourceResolver,
    private val mediaPreparer: LivePlaybackMediaPreparer,
    private val ingressClient: LiveDvrIngressClient,
    private val capacityCoordinator: LiveCapacityCoordinator,
    private val storageMonitor: LiveDvrStorageMonitor = LiveDvrStorageMonitor { directory ->
        LiveDvrStorageSpace(directory.usableSpace.coerceAtLeast(0L), directory.totalSpace.coerceAtLeast(0L))
    },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LiveDvrSessionGateway, Closeable {
    private data class SessionKey(val sourceId: String, val channelId: String)

    private data class SelectedMedia(val uri: String, val isHls: Boolean)

    private data class RecordingSink(
        val output: OutputStream,
        val onProgress: (Long) -> Unit,
        var bytesWritten: Long = 0L,
    )

    private class Session(
        val key: SessionKey,
        val sessionId: String,
        val capacityLeaseId: String,
        val store: LiveDvrRetainedStore,
    ) {
        val lock = Any()
        val recordingPins = linkedSetOf<String>()
        val recordingSinks = linkedMapOf<String, RecordingSink>()
        @Volatile var generation: Long = 0L
        @Volatile var state: LiveDvrSessionState = LiveDvrSessionState.STARTING
        @Volatile var playbackAttached: Boolean = false
        @Volatile var terminal: Boolean = false
        @Volatile var failure: Throwable? = null
        @Volatile var captureJob: Job? = null
        @Volatile var terminalSignal: CompletableDeferred<Unit> = CompletableDeferred()
        @Volatile var providerIngressOpenCount: Int = 0
        @Volatile var playbackAnchorEpochMillis: Long? = null
        @Volatile var playbackAnchorObserved: Boolean = false
        var activeSegment: LiveDvrIngressSegment? = null
    }

    private val gate = Any()
    private val storageWriteGate = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sessionsByKey = linkedMapOf<SessionKey, Session>()
    private val sessionsById = linkedMapOf<String, Session>()
    private val sessionByRecordingId = linkedMapOf<String, String>()

    init {
        // Retained prefixes are not deleted during process recreation. Normal close removes only
        // an unpinned temporary store after its last playback reader has closed.
    }

    override fun hasSession(sourceId: String, channelId: String): Boolean = synchronized(gate) {
        sessionsByKey[SessionKey(sourceId, channelId)]?.let {
            !it.terminal && it.failure == null
        } == true
    }

    override suspend fun attachPlayback(
        sourceId: String,
        channelId: String,
        media: PreparedPlaybackMedia,
    ): PreparedPlaybackMedia? {
        val candidates = selectMediaCandidates(media)
        if (candidates.isEmpty()) return null
        val key = SessionKey(sourceId, channelId)
        var created: Session? = null
        val session = synchronized(gate) {
            val existing = sessionsByKey[key]
            when {
                existing != null && !existing.terminal && existing.failure == null -> {
                    existing.playbackAttached = true
                    existing
                }
                existing != null && existing.recordingPins.isNotEmpty() -> return null
                else -> {
                    existing?.let(::removeSessionLocked)
                    val ownerLeaseId = capacityCoordinator.sessionLeaseId(sourceId, channelId)
                        ?: return null
                    newSessionLocked(
                        key = key,
                        capacityLeaseId = ownerLeaseId,
                    ).also {
                        it.playbackAttached = true
                        created = it
                    }
                }
            }
        }
        created?.let { startIngest(it, candidates) }
        return playbackMedia(session, session.store.latestSafeLivePoint())
    }

    override fun seekPlayback(
        sourceId: String,
        channelId: String,
        targetEpochMillis: Long,
    ): PreparedPlaybackMedia? {
        val session = synchronized(gate) { sessionsByKey[SessionKey(sourceId, channelId)] } ?: return null
        if (!isAvailableForPlayback(session)) return null
        val point = session.store.seekPoint(targetEpochMillis) ?: return null
        session.playbackAnchorEpochMillis = point.epochMillis
        return playbackMedia(session, point)
    }

    override fun goLive(sourceId: String, channelId: String): PreparedPlaybackMedia? {
        val session = synchronized(gate) { sessionsByKey[SessionKey(sourceId, channelId)] } ?: return null
        if (!isAvailableForPlayback(session)) return null
        val point = session.store.latestSafeLivePoint()
        session.playbackAnchorEpochMillis = point?.epochMillis
        return playbackMedia(session, point)
    }

    override fun playbackTimeline(sourceId: String, channelId: String): LiveDvrTimelineSnapshot? {
        val session = synchronized(gate) { sessionsByKey[SessionKey(sourceId, channelId)] } ?: return null
        val bounds = session.store.timelineBounds()
        return LiveDvrTimelineSnapshot(
            sessionId = session.sessionId,
            retainedStartEpochMillis = bounds.first,
            liveEdgeEpochMillis = bounds.second,
            playbackAnchorEpochMillis = session.playbackAnchorEpochMillis,
            playbackAnchorObserved = session.playbackAnchorObserved,
            seekable = !session.terminal && session.failure == null && bounds.first != null && bounds.second != null,
        )
    }

    override fun detachPlayback(sourceId: String, channelId: String): Boolean {
        val session = synchronized(gate) { sessionsByKey[SessionKey(sourceId, channelId)] }
            ?: return false
        val close = synchronized(gate) {
            if (sessionsById[session.sessionId] !== session) return@synchronized false
            session.playbackAttached = false
            session.recordingPins.isEmpty()
        }
        if (close) closeSession(session)
        return true
    }

    override suspend fun captureForRecording(
        sourceId: String,
        channelId: String,
        recordingId: String,
        endEpochMillis: Long,
        media: PreparedPlaybackMedia,
        output: OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        val candidates = selectMediaCandidates(media)
        if (candidates.isEmpty()) {
            throw LiveRecordingCaptureFailureException(
                LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                "This live stream format cannot be recorded safely.",
            )
        }
        val key = SessionKey(sourceId, channelId)
        var created: Session? = null
        val session = synchronized(gate) {
            val existing = sessionsByKey[key]
            when {
                existing != null && recordingId in existing.recordingPins -> throw IOException(
                    "This Live DVR recording is already attached to the active session."
                )
                existing != null && !existing.terminal && existing.failure == null -> existing
                existing != null && existing.recordingPins.isNotEmpty() -> throw IOException(
                    "The existing Live DVR session is finalizing and cannot admit another recording."
                )
                else -> {
                    existing?.let(::removeSessionLocked)
                    val ownerLeaseId = capacityCoordinator.sessionLeaseId(sourceId, channelId)
                        ?: throw IOException("The Live DVR capacity lease is no longer active.")
                    newSessionLocked(
                        key = key,
                        capacityLeaseId = ownerLeaseId,
                    ).also { created = it }
                }
            }.also { current ->
                current.recordingPins += recordingId
                sessionByRecordingId[recordingId] = current.sessionId
            }
        }
        synchronized(session.lock) {
            session.recordingSinks[recordingId] = RecordingSink(output, onProgress)
        }
        created?.let { startIngest(it, candidates) }

        try {
            while (System.currentTimeMillis() < endEpochMillis) {
                val signalled = withTimeoutOrNull(
                    (endEpochMillis - System.currentTimeMillis()).coerceIn(1L, TERMINAL_WAIT_STEP_MS),
                ) {
                    session.terminalSignal.await()
                    true
                } == true
                if (signalled) {
                    session.failure?.let { throw it.asRecordingFailure() }
                    if (System.currentTimeMillis() < endEpochMillis) {
                        throw IOException("The Live DVR ingress ended before the recording window.")
                    }
                }
            }
            session.failure?.let { throw it.asRecordingFailure() }
        } finally {
            synchronized(session.lock) {
                session.recordingSinks.remove(recordingId)
                session.activeSegment = null
                if (session.state !in setOf(LiveDvrSessionState.TERMINAL, LiveDvrSessionState.FAILED)) {
                    session.state = if (session.captureJob?.isActive == true) {
                        LiveDvrSessionState.ACTIVE
                    } else {
                        LiveDvrSessionState.FINALIZING
                    }
                }
            }
        }
    }

    override fun completeRecording(recordingId: String): Boolean {
        val session = synchronized(gate) {
            val sessionId = sessionByRecordingId.remove(recordingId) ?: return false
            sessionsById[sessionId]?.also { it.recordingPins.remove(recordingId) }
        } ?: return true
        synchronized(session.lock) { session.recordingSinks.remove(recordingId) }
        val close = synchronized(gate) {
            !session.playbackAttached && session.recordingPins.isEmpty()
        }
        if (close) closeSession(session)
        return true
    }

    override fun openReadHandle(sessionId: String, position: Long): LiveDvrReadHandle? {
        if (position < 0L) return null
        val session = synchronized(gate) { sessionsById[sessionId] } ?: return null
        val reader = session.store.openReadHandle(position) ?: return null
        session.playbackAnchorEpochMillis = reader.startEpochMillis
        session.playbackAnchorObserved = true
        return reader
    }

    override fun openLiveReadHandle(sessionId: String): LiveDvrReadHandle? {
        val session = synchronized(gate) { sessionsById[sessionId] } ?: return null
        val reader = session.store.openLiveReadHandle() ?: return null
        session.playbackAnchorEpochMillis = reader.startEpochMillis
        session.playbackAnchorObserved = true
        return reader
    }

    internal fun snapshot(sourceId: String, channelId: String): LiveDvrSessionSnapshot? {
        val session = synchronized(gate) { sessionsByKey[SessionKey(sourceId, channelId)] } ?: return null
        val recordingIds = synchronized(gate) { session.recordingPins.toSet() }
        return LiveDvrSessionSnapshot(
            sessionId = session.sessionId,
            generation = session.generation,
            state = session.state,
            providerIngressOpenCount = session.providerIngressOpenCount,
            playbackAttached = session.playbackAttached,
            recordingIds = recordingIds,
            capacityLeaseId = session.capacityLeaseId,
            retainedBytes = session.store.length,
            activeReaderCount = session.store.activeReaderCount(),
            timeline = session.store.timelineSnapshot(),
            failure = session.failure?.javaClass?.simpleName,
            failureCategory = (session.failure as? LiveRecordingCaptureFailureException)?.category,
        )
    }

    override fun close() {
        val sessions = synchronized(gate) { sessionsById.values.toList() }
        sessions.forEach(::closeSession)
        scope.cancel()
    }

    private fun newSessionLocked(key: SessionKey, capacityLeaseId: String): Session {
        if (!sessionDirectory.exists() && !sessionDirectory.mkdirs()) {
            throw storageFailure()
        }
        synchronized(storageWriteGate) { ensureStartHeadroom() }
        val id = UUID.randomUUID().toString()
        val store = try {
            LiveDvrRetainedStore(File(sessionDirectory, "$id.ts"))
        } catch (failure: IOException) {
            throw storageFailure(failure)
        }
        return Session(key, id, capacityLeaseId, store).also { session ->
            sessionsByKey[key] = session
            sessionsById[id] = session
        }
    }

    private fun startIngest(session: Session, candidates: List<SelectedMedia>) {
        require(candidates.isNotEmpty()) { "Live DVR ingress requires at least one media candidate." }
        synchronized(session.lock) {
            if (session.captureJob?.isActive == true) return
            session.generation += 1L
            val firstGeneration = session.generation
            session.failure = null
            session.terminal = false
            session.state = LiveDvrSessionState.STARTING
            session.terminalSignal = CompletableDeferred()
            session.captureJob = scope.launch {
                runIngest(session, firstGeneration, candidates)
            }
        }
    }

    private suspend fun runIngest(
        session: Session,
        initialGeneration: Long,
        initialCandidates: List<SelectedMedia>,
    ) {
        var generation = initialGeneration
        var candidates = initialCandidates
        var candidateIndex = 0
        var media = candidates[candidateIndex]
        var retries = 0
        while (scope.isActive && isCurrent(session, generation)) {
            session.providerIngressOpenCount += 1
            session.state = if (retries == 0) LiveDvrSessionState.STARTING else LiveDvrSessionState.RECONNECTING
            val retainedLengthBeforeAttempt = session.store.length
            var ingressReady = false
            val ingressOutput = SessionIngressOutput(session, generation, media.isHls)
            try {
                ingressClient.capture(
                    uri = media.uri,
                    isHls = media.isHls,
                    startEpochMillis = System.currentTimeMillis(),
                    endEpochMillis = Long.MAX_VALUE,
                    output = ingressOutput,
                    onProgress = {
                        if (
                            !ingressReady &&
                            session.store.length > retainedLengthBeforeAttempt &&
                            isCurrent(session, generation)
                        ) {
                            synchronized(session.lock) {
                                if (!session.terminal && session.generation == generation) {
                                    ingressReady = true
                                    retries = 0
                                    session.state = LiveDvrSessionState.ACTIVE
                                }
                            }
                        }
                    },
                )
                ingressOutput.finishCapture()
                finishSession(session, generation, null)
                return
            } catch (cancelled: CancellationException) {
                finishSession(session, generation, null)
                throw cancelled
            } catch (error: Throwable) {
                if (!isCurrent(session, generation)) return
                val wroteNoValidatedMedia = session.store.length == 0L
                val fallback = if (
                    wroteNoValidatedMedia && canTryCompatibilityFallback(error)
                ) {
                    candidates.getOrNull(candidateIndex + 1)
                } else {
                    null
                }
                if (fallback != null) {
                    candidateIndex += 1
                    media = fallback
                    retries = 0
                    session.state = LiveDvrSessionState.STARTING
                    continue
                }
                if (retries >= MAX_RECONNECTS || !isRetryable(error)) {
                    finishSession(session, generation, error)
                    return
                }
                retries += 1
                session.state = LiveDvrSessionState.RECONNECTING
                delay(RECONNECT_BACKOFF_MS[retries - 1])
                if (!isCurrent(session, generation)) return
                val target = PlaybackTarget.LiveChannel(SourceId(session.key.sourceId), session.key.channelId)
                val refreshed = try {
                    sourceResolver.resolve(target)?.let(mediaPreparer::prepare)?.let(::selectMediaCandidates)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val refreshedCandidates = refreshed?.takeIf { it.isNotEmpty() } ?: run {
                    finishSession(session, generation, error)
                    return
                }
                val compatibleCandidates = if (session.store.length > 0L) {
                    refreshedCandidates.filter { it.isHls == media.isHls }
                } else {
                    refreshedCandidates
                }
                if (compatibleCandidates.isEmpty()) {
                    finishSession(
                        session,
                        generation,
                        LiveRecordingCaptureFailureException(
                            LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                            "The refreshed Live source changed media representation.",
                        ),
                    )
                    return
                }
                candidates = compatibleCandidates
                candidateIndex = 0
                media = candidates[candidateIndex]
                synchronized(session.lock) {
                    if (session.terminal || session.generation != generation) return
                    session.generation += 1L
                    generation = session.generation
                    session.failure = null
                    session.terminal = false
                    session.terminalSignal = CompletableDeferred()
                    session.store.markDiscontinuity()
                }
            }
        }
    }

    private fun canTryCompatibilityFallback(error: Throwable): Boolean = when (error) {
        is LiveRecordingCaptureFailureException -> error.category in setOf(
            LiveRecordingFailureCategory.NETWORK,
            LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
            LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
        )
        else -> error is IOException
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is LiveRecordingCaptureFailureException -> error.category !in setOf(
            LiveRecordingFailureCategory.AUTHORIZATION,
            LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
            LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
            LiveRecordingFailureCategory.STORAGE,
        )
        else -> error is IOException
    }

    private fun Throwable.asRecordingFailure(): Throwable = when (this) {
        is LiveRecordingCaptureFailureException -> this
        else -> LiveRecordingCaptureFailureException(
            LiveRecordingFailureCategory.NETWORK,
            "The live stream ended before recording completed.",
            this,
        )
    }

    private fun finishSession(session: Session, generation: Long, error: Throwable?) {
        synchronized(session.lock) {
            if (session.generation != generation || session.terminal) return
            session.failure = error
            session.terminal = true
            session.state = if (error == null) LiveDvrSessionState.TERMINAL else LiveDvrSessionState.FAILED
            session.store.finish()
            session.terminalSignal.complete(Unit)
        }
    }

    private fun isCurrent(session: Session, generation: Long): Boolean = synchronized(gate) {
        sessionsById[session.sessionId] === session && session.generation == generation && !session.terminal
    }

    private fun isAvailableForPlayback(session: Session): Boolean = synchronized(gate) {
        sessionsById[session.sessionId] === session && !session.terminal && session.failure == null
    }

    private fun playbackMedia(session: Session, point: LiveDvrRetainedPoint?): PreparedPlaybackMedia {
        session.playbackAnchorEpochMillis = point?.epochMillis
        session.playbackAnchorObserved = false
        return PreparedPlaybackMedia(
            uri = dvrUri(session.sessionId, point),
            mimeType = MPEG_TS_MIME_TYPE,
            usesProviderConnection = true,
            liveDvrSessionId = session.sessionId,
            liveDvrStartEpochMillis = point?.epochMillis,
        )
    }

    private fun ensureStartHeadroom() {
        if (!LiveDvrStoragePolicy.canStart(storageMonitor.space(sessionDirectory))) throw storageFailure()
    }

    private fun ensureWriteHeadroom(incomingBytes: Long, recordingOutputCount: Int) {
        if (!LiveDvrStoragePolicy.canWrite(
                space = storageMonitor.space(sessionDirectory),
                incomingBytes = incomingBytes,
                recordingOutputCount = recordingOutputCount,
            )
        ) {
            throw storageFailure()
        }
    }

    private fun storageFailure(cause: Throwable? = null) = LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.STORAGE,
        "Device storage is too low to continue Live DVR capture safely.",
        cause,
    )

    private fun closeSession(session: Session) {
        val removed = synchronized(gate) {
            if (sessionsById[session.sessionId] !== session) return@synchronized false
            session.terminal = true
            sessionsById.remove(session.sessionId)
            sessionsByKey.remove(session.key, session)
            sessionByRecordingId.entries.removeAll { it.value == session.sessionId }
            true
        }
        if (!removed) return
        session.captureJob?.cancel(CancellationException("Live DVR session has no attachments"))
        synchronized(session.lock) {
            session.recordingSinks.clear()
            session.recordingPins.clear()
            session.terminal = true
            session.state = LiveDvrSessionState.TERMINAL
            session.store.finish()
            session.terminalSignal.complete(Unit)
        }
        capacityCoordinator.release(session.capacityLeaseId)
    }

    private fun removeSessionLocked(session: Session) {
        session.terminal = true
        sessionsById.remove(session.sessionId)
        sessionsByKey.remove(session.key, session)
        sessionByRecordingId.entries.removeAll { it.value == session.sessionId }
        session.captureJob?.cancel(CancellationException("Replacing inactive Live DVR session"))
        session.store.finish()
        capacityCoordinator.release(session.capacityLeaseId)
    }

    private fun selectMediaCandidates(media: PreparedPlaybackMedia): List<SelectedMedia> {
        val candidates = listOfNotNull(
            PreparedPlaybackAlternative(media.uri, media.mimeType),
            media.fallback,
        )
        return candidates.mapNotNull { candidate ->
            when {
                LiveRecordingCaptureClient.isHls(candidate.uri, candidate.mimeType) ->
                    SelectedMedia(candidate.uri, true)
                isDirectTransportStream(candidate.uri, candidate.mimeType) ->
                    SelectedMedia(candidate.uri, false)
                else -> null
            }
        }.distinct()
    }

    private fun isDirectTransportStream(uri: String, mimeType: String?): Boolean {
        if (mimeType?.contains("mp2t", ignoreCase = true) == true) return true
        val segment = uri.substringBefore('?').substringAfterLast('/')
        val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isBlank() || extension.equals("ts", ignoreCase = true)
    }

    private fun dvrUri(sessionId: String, point: LiveDvrRetainedPoint?): String = if (point == null) {
        "$DVR_SCHEME://$sessionId/live.ts?start=live"
    } else {
        val anchor = point.epochMillis?.let { "&anchor=$it" }.orEmpty()
        "$DVR_SCHEME://$sessionId/live.ts?offset=${point.byteOffset}$anchor"
    }

    private fun recordingLeaseId(recordingId: String): String = "recording:$recordingId"

    private inner class SessionIngressOutput(
        private val session: Session,
        private val generation: Long,
        private val isHls: Boolean,
    ) : OutputStream(), LiveDvrSegmentBoundaryOutput {
        private var directPending = ByteArray(0)
        private var directValidated = false
        private var pendingHlsSegment: LiveDvrIngressSegment? = null
        private var pendingHlsBytes: ByteArrayOutputStream? = null

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            if (!isCurrent(session, generation)) throw IOException("The Live DVR session is no longer active.")
            synchronized(storageWriteGate) {
                synchronized(session.lock) {
                    if (session.terminal || session.generation != generation) {
                        throw IOException("The Live DVR session is no longer active.")
                    }
                    if (isHls) stageHlsBytes(buffer, offset, length) else acceptDirectBytes(buffer, offset, length)
                }
            }
        }

        private fun acceptDirectBytes(buffer: ByteArray, offset: Int, length: Int) {
            val combined = ByteArray(directPending.size + length)
            directPending.copyInto(combined)
            buffer.copyInto(combined, directPending.size, offset, offset + length)
            val completeLength = combined.size - combined.size % TS_PACKET_BYTES
            val validLength = validTransportStreamPrefix(combined, completeLength)
            val invalidPacket = validLength < completeLength
            val minimumSniffBytes = MIN_DIRECT_SNIFF_PACKETS * TS_PACKET_BYTES
            if (!directValidated && invalidPacket && validLength < minimumSniffBytes) {
                directPending = ByteArray(0)
                throw unsupportedTransportStream()
            }
            if (!directValidated && validLength >= minimumSniffBytes) directValidated = true
            if (directValidated && validLength > 0) appendValidated(combined, 0, validLength)
            if (invalidPacket) {
                directPending = ByteArray(0)
                throw unsupportedTransportStream()
            }
            directPending = if (directValidated) {
                combined.copyOfRange(completeLength, combined.size)
            } else {
                combined
            }
        }

        private fun stageHlsBytes(buffer: ByteArray, offset: Int, length: Int) {
            val staged = pendingHlsBytes
                ?: throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "The HLS segment could not be staged safely.",
                )
            if (staged.size() > MAX_STAGED_HLS_SEGMENT_BYTES - length) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "The HLS segment is too large to stage safely.",
                )
            }
            staged.write(buffer, offset, length)
        }

        private fun appendValidated(buffer: ByteArray, offset: Int, length: Int) {
            try {
                ensureWriteHeadroom(length.toLong(), session.recordingSinks.size)
                session.store.append(buffer, offset, length)
                session.recordingSinks.forEach { (_, sink) ->
                    sink.output.write(buffer, offset, length)
                    sink.bytesWritten += length
                    sink.onProgress(sink.bytesWritten)
                }
            } catch (failure: LiveRecordingCaptureFailureException) {
                throw failure
            } catch (failure: IOException) {
                throw storageFailure(failure)
            }
        }

        fun finishCapture() {
            if (!isHls && !directValidated) throw unsupportedTransportStream()
            if (isHls && pendingHlsSegment != null) endSegment(complete = false)
        }

        override fun flush() {
            synchronized(session.lock) {
                session.store.flush()
                session.recordingSinks.values.forEach { it.output.flush() }
            }
        }

        override fun close() = flush()

        override fun beginSegment(segment: LiveDvrIngressSegment): Boolean = synchronized(session.lock) {
            if (!isHls || session.terminal || session.generation != generation || pendingHlsSegment != null) {
                return@synchronized false
            }
            pendingHlsSegment = segment
            pendingHlsBytes = ByteArrayOutputStream()
            true
        }

        override fun endSegment(complete: Boolean) {
            val segment: LiveDvrIngressSegment
            val bytes: ByteArray
            synchronized(session.lock) {
                segment = pendingHlsSegment ?: return
                bytes = pendingHlsBytes?.toByteArray() ?: ByteArray(0)
                pendingHlsSegment = null
                pendingHlsBytes = null
            }
            if (!complete) return
            if (!isCompleteTransportStream(bytes)) throw unsupportedTransportStream()
            if (!isCurrent(session, generation)) throw IOException("The Live DVR session is no longer active.")
            synchronized(storageWriteGate) {
                synchronized(session.lock) {
                    if (session.terminal || session.generation != generation) {
                        throw IOException("The Live DVR session is no longer active.")
                    }
                    if (!session.store.beginSegment(segment)) return
                    var storeCommitted = false
                    try {
                        ensureWriteHeadroom(bytes.size.toLong(), session.recordingSinks.size)
                        session.store.append(bytes, 0, bytes.size)
                        storeCommitted = true
                        session.recordingSinks.forEach { (_, sink) ->
                            sink.output.write(bytes)
                            sink.bytesWritten += bytes.size
                            sink.onProgress(sink.bytesWritten)
                        }
                    } catch (failure: LiveRecordingCaptureFailureException) {
                        throw failure
                    } catch (failure: IOException) {
                        throw storageFailure(failure)
                    } finally {
                        session.store.endSegment(storeCommitted)
                        session.activeSegment = null
                    }
                }
            }
        }
    }

    private fun validTransportStreamPrefix(bytes: ByteArray, length: Int): Int {
        var offset = 0
        while (offset < length) {
            if ((bytes[offset].toInt() and 0xff) != TS_SYNC_BYTE) return offset
            offset += TS_PACKET_BYTES
        }
        return length
    }

    private fun isCompleteTransportStream(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() &&
            bytes.size % TS_PACKET_BYTES == 0 &&
            validTransportStreamPrefix(bytes, bytes.size) == bytes.size

    private fun unsupportedTransportStream() = LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
        "The live source did not contain a complete MPEG-TS stream.",
    )

    companion object {
        const val DVR_SCHEME = "ownplaydvr"
        const val MPEG_TS_MIME_TYPE = "video/mp2t"
        private const val MAX_RECONNECTS = 3
        private const val TERMINAL_WAIT_STEP_MS = 500L
        private const val TS_PACKET_BYTES = 188
        private const val TS_SYNC_BYTE = 0x47
        private const val MIN_DIRECT_SNIFF_PACKETS = 64
        private const val MAX_STAGED_HLS_SEGMENT_BYTES = 16 * 1024 * 1024
        private val RECONNECT_BACKOFF_MS = longArrayOf(250L, 500L, 1_000L)
    }
}

/** Append-only ephemeral buffer with byte offsets and segment/time boundaries for DVR seeking. */
private class LiveDvrRetainedStore(private val file: File) : Closeable {
    private val monitor = Object()
    private val randomAccess = RandomAccessFile(file, "rw")
    private val timeline = mutableListOf<LiveDvrTimelineEntry>()
    private val segmentIdentities = hashSetOf<String>()
    private var retainedLength = randomAccess.length()
    private var ended = false
    private var readerCount = 0
    private var pendingSegment: LiveDvrIngressSegment? = null
    private var pendingSegmentOffset = 0L
    private var pendingSegmentStartEpochMillis = 0L
    private var lastDirectArrivalEpochMs = 0L

    val length: Long
        get() = synchronized(monitor) { retainedLength }

    fun append(buffer: ByteArray, offset: Int, count: Int) {
        synchronized(monitor) {
            if (ended) throw IOException("The retained Live DVR store is closed.")
            val startOffset = retainedLength
            randomAccess.seek(startOffset)
            try {
                randomAccess.write(buffer, offset, count)
            } catch (failure: IOException) {
                runCatching { randomAccess.setLength(startOffset) }
                throw failure
            }
            retainedLength += count
            if (pendingSegment == null) {
                val now = System.currentTimeMillis()
                val latest = timeline.lastOrNull()
                if (
                    latest != null && latest.identity == null && latest.endOffset == startOffset &&
                    lastDirectArrivalEpochMs != 0L &&
                    now - lastDirectArrivalEpochMs <= DIRECT_TIMELINE_WINDOW_MS
                ) {
                    timeline[timeline.lastIndex] = latest.copy(
                        endOffset = retainedLength,
                        durationMillis = (now - latest.startEpochMillis).coerceAtLeast(0L),
                    )
                } else {
                    timeline += LiveDvrTimelineEntry(
                        identity = null,
                        startOffset = startOffset,
                        endOffset = retainedLength,
                        startEpochMillis = now,
                        durationMillis = 0L,
                        discontinuitySequence = 0L,
                    )
                }
                lastDirectArrivalEpochMs = now
            }
            monitor.notifyAll()
        }
    }

    fun beginSegment(segment: LiveDvrIngressSegment): Boolean = synchronized(monitor) {
        if (ended || segment.identity in segmentIdentities) return@synchronized false
        pendingSegment = segment
        pendingSegmentOffset = retainedLength
        pendingSegmentStartEpochMillis = segment.programTimeEpochMs
            ?: timeline.lastOrNull()?.endEpochMillis
            ?: System.currentTimeMillis()
        true
    }

    fun endSegment(complete: Boolean) {
        synchronized(monitor) {
            val segment = pendingSegment ?: return
            if (complete && retainedLength > pendingSegmentOffset) {
                segmentIdentities += segment.identity
                timeline += LiveDvrTimelineEntry(
                    identity = segment.identity,
                    startOffset = pendingSegmentOffset,
                    endOffset = retainedLength,
                    startEpochMillis = pendingSegmentStartEpochMillis,
                    durationMillis = segment.durationMs,
                    discontinuitySequence = segment.discontinuitySequence,
                )
            }
            pendingSegment = null
            pendingSegmentStartEpochMillis = 0L
            monitor.notifyAll()
        }
    }

    fun markDiscontinuity() = synchronized(monitor) {
        lastDirectArrivalEpochMs = 0L
        monitor.notifyAll()
    }

    fun flush() = synchronized(monitor) { randomAccess.fd.sync() }

    fun finish() {
        synchronized(monitor) {
            ended = true
            pendingSegment = null
            monitor.notifyAll()
            cleanupIfUnused()
        }
    }

    fun timelineSnapshot(): List<LiveDvrTimelineEntry> = synchronized(monitor) { timeline.toList() }

    fun activeReaderCount(): Int = synchronized(monitor) { readerCount }

    fun timelineBounds(): Pair<Long?, Long?> = synchronized(monitor) {
        val segments = seekableSegments()
        segments.firstOrNull()?.startEpochMillis to segments.lastOrNull()?.endEpochMillis
    }

    fun latestSafeLivePoint(): LiveDvrRetainedPoint? = synchronized(monitor) {
        val segments = seekableSegments()
        when {
            pendingSegment != null -> LiveDvrRetainedPoint(pendingSegmentOffset, pendingSegmentStartEpochMillis)
            segments.isNotEmpty() -> LiveDvrRetainedPoint(segments.last().startOffset, segments.last().startEpochMillis)
            retainedLength > 0L -> LiveDvrRetainedPoint(latestDirectPacketBoundary(), null)
            else -> null
        }
    }

    fun seekPoint(targetEpochMillis: Long): LiveDvrRetainedPoint? = synchronized(monitor) {
        val segments = seekableSegments()
        val first = segments.firstOrNull() ?: return@synchronized null
        val last = segments.last()
        if (targetEpochMillis < first.startEpochMillis || targetEpochMillis > last.endEpochMillis) {
            return@synchronized null
        }
        val selected = segments.firstOrNull { targetEpochMillis < it.endEpochMillis } ?: last
        LiveDvrRetainedPoint(selected.startOffset, selected.startEpochMillis)
    }

    private fun seekableSegments(): List<LiveDvrTimelineEntry> = timeline.filter { entry ->
        entry.identity != null && entry.endOffset > entry.startOffset &&
            entry.startEpochMillis > 0L && entry.durationMillis > 0L
    }

    private fun epochAtBoundary(position: Long): Long? = when {
        pendingSegment != null && pendingSegmentOffset == position -> pendingSegmentStartEpochMillis
        else -> seekableSegments().firstOrNull { it.startOffset == position }?.startEpochMillis
    }

    private fun latestDirectPacketBoundary(): Long {
        val packetBytes = 188L
        val packetCount = retainedLength / packetBytes
        if (packetCount < 3L) return 0L
        var offset = (packetCount - 3L) * packetBytes
        val earliestOffset = (packetCount - 12L).coerceAtLeast(0L) * packetBytes
        while (offset >= earliestOffset) {
            val valid = (0L..2L).all { packetIndex ->
                randomAccess.seek(offset + packetIndex * packetBytes)
                randomAccess.read() == 0x47
            }
            if (valid) return offset
            offset -= packetBytes
        }
        return 0L
    }

    fun openReadHandle(position: Long): LiveDvrReadHandle? = synchronized(monitor) {
        if (position > retainedLength || (ended && position >= retainedLength)) return@synchronized null
        readerCount += 1
        StoreReader(position, epochAtBoundary(position))
    }

    fun openLiveReadHandle(): LiveDvrReadHandle? = synchronized(monitor) {
        if (ended) return@synchronized null
        val point = latestSafeLivePoint()
        val startPosition = point?.byteOffset ?: retainedLength
        readerCount += 1
        StoreReader(startPosition, point?.epochMillis)
    }

    private fun readAt(position: Long, buffer: ByteArray, offset: Int, requested: Int): Int = synchronized(monitor) {
        while (position >= retainedLength && !ended) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("DVR playback read interrupted")
            try {
                monitor.wait(250L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("DVR playback read interrupted").apply { initCause(interrupted) }
            }
        }
        if (position >= retainedLength && ended) return@synchronized -1
        val count = minOf(requested.toLong(), retainedLength - position).toInt()
        randomAccess.seek(position)
        randomAccess.read(buffer, offset, count)
    }

    private fun readerClosed() = synchronized(monitor) {
        readerCount = (readerCount - 1).coerceAtLeast(0)
        cleanupIfUnused()
    }

    private fun cleanupIfUnused() {
        if (!ended || readerCount != 0) return
        runCatching { randomAccess.close() }
        runCatching { file.delete() }
    }

    override fun close() = finish()

    private inner class StoreReader(
        startPosition: Long,
        override val startEpochMillis: Long?,
    ) : LiveDvrReadHandle {
        private var position = startPosition
        private var closed = false

        override val availableLength: Long
            get() = length

        override val ended: Boolean
            get() = synchronized(monitor) { this@LiveDvrRetainedStore.ended }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "DVR reader is closed" }
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            val count = readAt(position, buffer, offset, length)
            if (count > 0) position += count
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            readerClosed()
        }
    }
}

private const val DIRECT_TIMELINE_WINDOW_MS = 1_000L
