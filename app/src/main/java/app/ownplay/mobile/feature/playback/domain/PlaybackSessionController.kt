package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class PlaybackSessionController internal constructor(
    private val sourceResolver: LivePlaybackSourceResolver,
    private val mediaPreparer: LivePlaybackMediaPreparer,
    private val playbackEngine: PlaybackEngine,
    private val libraryMediaResolver: LibraryPlaybackMediaResolver? = null,
    private val catchUpMediaResolver: CatchUpPlaybackMediaResolver? = null,
    private val playbackProgressEngine: PlaybackProgressEngine? = null,
    private val libraryProgressStore: LibraryPlaybackProgressStore? = null,
    private val catchUpProgressStore: CatchUpPlaybackProgressStore? = null,
    private val liveCapacityCoordinator: LiveCapacityCoordinator? = null,
    private val liveDvrSessionGateway: LiveDvrSessionGateway? = null,
    private val onRecordingPreemptedByPlayback: suspend (List<String>) -> Boolean = { true },
    reconnectDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PlaybackSessionState())
    private val reconnectScope = CoroutineScope(SupervisorJob() + reconnectDispatcher)

    @Volatile
    private var activeMediaRevision: Long? = null

    @Volatile
    private var sessionGeneration: Long = 0L

    private var activeFallbackMedia: PreparedPlaybackMedia? = null
    private var fallbackAttempted: Boolean = false
    private var liveReconnectAttempts: Int = 0
    private var liveReconnectJob: Job? = null
    private var liveReconnectFailurePending: Boolean = false
    private var presentationBeforePictureInPicture: PlaybackPresentation? = null
    private var activeLiveLeaseSourceId: String? = null
    private var activeLiveLeaseChannelId: String? = null
    private var activeLiveDvrSessionId: String? = null
    private var activeLiveDvrAnchorEpochMillis: Long? = null

    val state: StateFlow<PlaybackSessionState> = mutableState.asStateFlow()

    init {
        playbackEngine.setEventListener(::onPlaybackEngineEvent)
    }

    suspend fun activateLiveChannel(target: PlaybackTarget.LiveChannel) {
        mutex.withLock {
            val current = mutableState.value
            val targetChanged = current.target != target
            val recoverSameTarget = !targetChanged && current.readiness in setOf(
                PlaybackReadiness.IDLE,
                PlaybackReadiness.UNAVAILABLE,
            )
            if (targetChanged) {
                checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.TARGET_SWITCH)
                presentationBeforePictureInPicture = null
            }
            mutableState.value = PlaybackSessionPolicy.activateLiveChannel(current, target)

            if (!targetChanged && !recoverSameTarget) return
            beginNewSessionAttempt()

            replaceTargetMedia(target) {
                prepareLiveMedia(target)
            }
        }
    }

    suspend fun activateCatchUp(
        target: PlaybackTarget.CatchUp,
        resumePositionMs: Long? = null,
    ) {
        mutex.withLock {
            val current = mutableState.value
            val targetChanged = current.target != target
            val recoverSameTarget = !targetChanged && current.readiness in setOf(
                PlaybackReadiness.IDLE,
                PlaybackReadiness.UNAVAILABLE,
            )
            if (targetChanged) {
                checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.TARGET_SWITCH)
                presentationBeforePictureInPicture = null
            }
            mutableState.value = PlaybackSessionPolicy.activateCatchUp(current, target)

            val replaced = if (targetChanged || recoverSameTarget) {
                beginNewSessionAttempt()
                replaceTargetMedia(target) {
                    catchUpMediaResolver?.resolve(target)
                }
            } else {
                true
            }
            if (replaced && resumePositionMs != null && resumePositionMs > 0L) {
                playbackProgressEngine?.seekTo(resumePositionMs)
            }
        }
    }

    suspend fun activateLibraryMedia(target: PlaybackTarget.Library) {
        mutex.withLock {
            val current = mutableState.value
            val targetChanged = current.target != target
            val recoverSameTarget = !targetChanged && current.readiness in setOf(
                PlaybackReadiness.IDLE,
                PlaybackReadiness.UNAVAILABLE,
            )
            if (targetChanged) {
                checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.TARGET_SWITCH)
                presentationBeforePictureInPicture = null
            }
            mutableState.value = PlaybackSessionPolicy.activateLibraryMedia(current, target)

            if (!targetChanged && !recoverSameTarget) return
            beginNewSessionAttempt()

            val replaced = replaceTargetMedia(target) {
                libraryMediaResolver?.resolve(target)
            }
            if (replaced) {
                libraryProgressStore?.markDeliberateViewingAction(target)
                restoreLibraryProgress(target)
            }
        }
    }

    fun enterFullscreen() {
        mutableState.value = PlaybackSessionPolicy.present(
            current = mutableState.value,
            presentation = PlaybackPresentation.FULLSCREEN,
        )
    }

    fun enterPictureInPicture() {
        val current = mutableState.value
        if (!PlaybackPictureInPicturePolicy.isEligible(current)) return
        if (current.presentation == PlaybackPresentation.PICTURE_IN_PICTURE) return

        presentationBeforePictureInPicture = current.presentation
        checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.PICTURE_IN_PICTURE)
        mutableState.value = PlaybackSessionPolicy.present(
            current = current,
            presentation = PlaybackPresentation.PICTURE_IN_PICTURE,
        )
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (isInPictureInPictureMode) {
            enterPictureInPicture()
            return
        }

        val current = mutableState.value
        if (current.presentation != PlaybackPresentation.PICTURE_IN_PICTURE) return

        val restoredPresentation = restoredPresentationAfterPictureInPicture(
            target = current.target,
            priorPresentation = presentationBeforePictureInPicture,
        )
        presentationBeforePictureInPicture = null
        if (restoredPresentation != null) {
            mutableState.value = PlaybackSessionPolicy.present(
                current = current,
                presentation = restoredPresentation,
            )
        }
    }

    fun returnToPreview() {
        val current = mutableState.value
        if (current.target !is PlaybackTarget.LiveChannel && current.target !is PlaybackTarget.CatchUp) return
        mutableState.value = PlaybackSessionPolicy.present(
            current = current,
            presentation = PlaybackPresentation.PREVIEW,
        )
    }

    fun selectAudioTrack(trackId: String?): Boolean =
        applyTrackSelection(
            issueWhenUnsupported = PlaybackTrackSelectionIssue.AUDIO_UNSUPPORTED,
            result = playbackEngine.selectAudioTrack(trackId),
            fallbackReason = PlaybackFallbackReason.AUDIO_SELECTION,
        )

    fun selectSubtitleTrack(trackId: String?): Boolean =
        applyTrackSelection(
            issueWhenUnsupported = PlaybackTrackSelectionIssue.SUBTITLE_UNSUPPORTED,
            result = playbackEngine.selectSubtitleTrack(trackId),
            fallbackReason = null,
        )

    fun play(): Boolean {
        val current = mutableState.value
        if (current.target == null || current.readiness == PlaybackReadiness.UNAVAILABLE) return false
        if (!playbackEngine.play()) return false
        if (current.target is PlaybackTarget.Library) {
            libraryProgressStore?.markDeliberateViewingAction(current.target)
        }
        mutableState.value = current.copy(playWhenReady = true)
        return true
    }

    fun pause(): Boolean {
        val current = mutableState.value
        if (current.target == null || current.readiness == PlaybackReadiness.UNAVAILABLE) return false
        if (!playbackEngine.pause()) return false
        checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.PAUSE)
        mutableState.value = current.copy(playWhenReady = false)
        return true
    }

    fun seekTo(positionMs: Long): Boolean {
        if (positionMs < 0L || playbackProgressEngine?.seekTo(positionMs) != true) return false
        if (mutableState.value.endedNaturally) {
            mutableState.value = mutableState.value.copy(endedNaturally = false)
        }
        return true
    }

    /** Seeks Live DVR by UTC media time; the session owner maps it to a retained segment boundary. */
    suspend fun seekLiveDvrToEpoch(targetEpochMillis: Long): Boolean = mutex.withLock {
        if (targetEpochMillis <= 0L) return@withLock false
        val target = mutableState.value.target as? PlaybackTarget.LiveChannel ?: return@withLock false
        val media = liveDvrSessionGateway?.seekPlayback(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
            targetEpochMillis = targetEpochMillis,
        ) ?: return@withLock false
        reattachLiveDvrMedia(target, media)
    }

    /** Returns to the latest safe retained segment without reopening the provider transport. */
    suspend fun goLive(): Boolean = mutex.withLock {
        val target = mutableState.value.target as? PlaybackTarget.LiveChannel ?: return@withLock false
        val media = liveDvrSessionGateway?.goLive(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
        ) ?: return@withLock false
        reattachLiveDvrMedia(target, media)
    }

    internal fun liveDvrTimelineSnapshot(): LiveDvrTimelineSnapshot? {
        val target = mutableState.value.target as? PlaybackTarget.LiveChannel ?: return null
        val snapshot = liveDvrSessionGateway?.playbackTimeline(
            sourceId = target.sourceId.value,
            channelId = target.channelId,
        ) ?: return null
        val anchor = if (activeLiveDvrSessionId == snapshot.sessionId) {
            if (snapshot.playbackAnchorObserved) {
                snapshot.playbackAnchorEpochMillis
            } else {
                activeLiveDvrAnchorEpochMillis ?: snapshot.playbackAnchorEpochMillis
            }
        } else {
            snapshot.playbackAnchorEpochMillis
        }
        val playbackPosition = runCatching { playbackProgressEngine?.positionSnapshot()?.positionMs }.getOrNull()
        val currentEpoch = anchor?.let { start -> playbackPosition?.let { safeEpochAdd(start, it) } }
        return snapshot.copy(
            playbackAnchorEpochMillis = anchor,
            currentPlaybackEpochMillis = currentEpoch,
        )
    }

    fun setSpeed(speed: Float): Boolean {
        val current = mutableState.value
        if (current.target !is PlaybackTarget.Library) return false
        if (!PlaybackSpeedPolicy.isSupported(speed)) return false
        if (!playbackEngine.setSpeed(speed)) return false
        mutableState.value = current.copy(speed = speed)
        return true
    }

    internal fun positionSnapshot(): PlaybackPositionSnapshot? =
        runCatching { playbackProgressEngine?.positionSnapshot() }.getOrNull()

    fun checkpointProgress() {
        checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.PERIODIC)
    }

    fun setPlayerVolume(volume: Float): Boolean =
        playbackEngine.setVolume(volume.coerceIn(0f, 1f))

    suspend fun retryActiveTarget(): Boolean = mutex.withLock {
        val target = mutableState.value.target ?: return@withLock false
        beginNewSessionAttempt()
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.PREPARING,
            playWhenReady = true,
            tracks = PlaybackTrackSnapshot(),
            fallback = PlaybackFallbackState(),
            endedNaturally = false,
        )

        val replaced = when (target) {
            is PlaybackTarget.LiveChannel -> replaceTargetMedia(target) {
                prepareLiveMedia(target)
            }

            is PlaybackTarget.CatchUp -> replaceTargetMedia(target) {
                catchUpMediaResolver?.resolve(target)
            }

            is PlaybackTarget.Library -> replaceTargetMedia(target) {
                libraryMediaResolver?.resolve(target)
            }
        }
        if (replaced && target is PlaybackTarget.Library) {
            libraryProgressStore?.markDeliberateViewingAction(target)
            restoreLibraryProgress(target)
        }
        replaced
    }

    fun reconcileActiveSource(activeSourceId: SourceId?) {
        val target = mutableState.value.target ?: return
        if (
            target is PlaybackTarget.CatchUp &&
            (target.localMediaUri != null || target.offlineDownloadId != null)
        ) return
        if (activeSourceId != target.sourceId) {
            clear()
        }
    }

    suspend fun revalidateActiveTarget() {
        val target = mutableState.value.target ?: return
        val stillResolvable = try {
            when (target) {
                is PlaybackTarget.LiveChannel -> sourceResolver.resolve(target) != null
                is PlaybackTarget.CatchUp -> catchUpMediaResolver?.resolve(target) != null
                is PlaybackTarget.Library -> libraryMediaResolver?.resolve(target) != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

        if (mutableState.value.target == target && !stillResolvable) {
            clear()
        }
    }

    internal fun reportProlongedBuffering() {
        maybeAttemptFallback(PlaybackFallbackReason.PROLONGED_BUFFERING)
    }

    fun clear() {
        beginNewSessionAttempt()
        checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.CLEAR)
        presentationBeforePictureInPicture = null
        resetActiveMedia()
        playbackEngine.clear()
        releaseLiveLease()
        mutableState.value = PlaybackSessionState()
    }

    internal fun release() {
        beginNewSessionAttempt()
        reconnectScope.cancel()
        checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.RELEASE)
        presentationBeforePictureInPicture = null
        resetActiveMedia()
        playbackEngine.release()
        releaseLiveLease()
        mutableState.value = PlaybackSessionState()
        libraryProgressStore?.close()
        catchUpProgressStore?.close()
    }

    private fun restoredPresentationAfterPictureInPicture(
        target: PlaybackTarget?,
        priorPresentation: PlaybackPresentation?,
    ): PlaybackPresentation? = when (target) {
        is PlaybackTarget.LiveChannel,
        is PlaybackTarget.CatchUp,
        -> when (priorPresentation) {
            PlaybackPresentation.FULLSCREEN -> PlaybackPresentation.FULLSCREEN
            PlaybackPresentation.PREVIEW -> PlaybackPresentation.PREVIEW
            else -> PlaybackPresentation.PREVIEW
        }

        is PlaybackTarget.Library -> PlaybackPresentation.FULLSCREEN
        null -> null
    }

    private suspend fun restoreLibraryProgress(target: PlaybackTarget.Library) {
        val progress = try {
            libraryProgressStore?.load(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val resumePosition = LibraryPlaybackProgressPolicy.resumePosition(progress) ?: return
        if (mutableState.value.target == target) {
            playbackProgressEngine?.seekTo(resumePosition)
        }
    }

    private fun checkpointActivePersistedProgress(
        boundary: LibraryPlaybackProgressBoundary = LibraryPlaybackProgressBoundary.PERIODIC,
    ) {
        val target = mutableState.value.target ?: return
        if (target !is PlaybackTarget.Library && target !is PlaybackTarget.CatchUp) return
        val snapshot = try {
            playbackProgressEngine?.positionSnapshot()
        } catch (_: Exception) {
            null
        }
        val progress = LibraryPlaybackProgressPolicy.checkpoint(snapshot) ?: return
        when (target) {
            is PlaybackTarget.Library -> libraryProgressStore?.record(target, progress, boundary)
            is PlaybackTarget.CatchUp -> catchUpProgressStore?.record(target, progress)
            is PlaybackTarget.LiveChannel -> Unit
        }
    }

    private suspend fun replaceTargetMedia(
        target: PlaybackTarget,
        expectedGeneration: Long = sessionGeneration,
        resolve: suspend () -> PreparedPlaybackMedia?,
    ): Boolean {
        if (!isCurrentSession(target, expectedGeneration)) return false
        resetActiveMedia()
        playbackEngine.clear()
        var providerLeaseAcquired = false
        val requiresProviderBeforeResolve = when (target) {
            is PlaybackTarget.LiveChannel -> true
            is PlaybackTarget.CatchUp -> target.localMediaUri == null && target.offlineDownloadId == null
            is PlaybackTarget.Library -> false
        }
        if (liveCapacityCoordinator != null && requiresProviderBeforeResolve) {
            if (!acquireProviderLease(target)) {
                releaseLeaseUnlessMatching(target)
                if (isCurrentSession(target, expectedGeneration)) markTargetUnavailable()
                return false
            }
            providerLeaseAcquired = true
        }
        if (!isCurrentSession(target, expectedGeneration)) return false
        val media = try {
            resolve()
        } catch (cancelled: CancellationException) {
            recoverCancelledTarget(target, expectedGeneration)
            throw cancelled
        } catch (_: Exception) {
            null
        }

        if (!isCurrentSession(target, expectedGeneration)) return false

        if (media == null) {
            releaseLiveLease()
            markTargetUnavailable()
            return false
        }

        val requiresProviderConnection = target is PlaybackTarget.LiveChannel || media.usesProviderConnection
        if (requiresProviderConnection && liveCapacityCoordinator != null && !providerLeaseAcquired) {
            if (!acquireProviderLease(target)) {
                releaseLeaseUnlessMatching(target)
                if (isCurrentSession(target, expectedGeneration)) markTargetUnavailable()
                return false
            }
            providerLeaseAcquired = true
        } else if (!requiresProviderConnection) {
            releaseLiveLease()
        }

        if (!isCurrentSession(target, expectedGeneration)) return false

        activeFallbackMedia = media.fallback?.let {
            PreparedPlaybackMedia(
                uri = it.uri,
                mimeType = it.mimeType,
            )
        }

        return try {
            activeMediaRevision = playbackEngine.replace(media.primaryOnly())
            activeLiveDvrSessionId = media.liveDvrSessionId
            activeLiveDvrAnchorEpochMillis = media.liveDvrStartEpochMillis
            if (!mutableState.value.playWhenReady) playbackEngine.pause()
            playbackEngine.setSpeed(mutableState.value.speed)
            true
        } catch (cancelled: CancellationException) {
            recoverCancelledTarget(target, expectedGeneration)
            throw cancelled
        } catch (_: Exception) {
            resetActiveMedia()
            playbackEngine.clear()
            releaseLiveLease()
            mutableState.value = mutableState.value.copy(
                readiness = PlaybackReadiness.UNAVAILABLE,
                playWhenReady = false,
            )
            false
        }
    }

    private suspend fun acquireProviderLease(target: PlaybackTarget): Boolean {
        val coordinator = liveCapacityCoordinator ?: return true
        val resourceId = target.capacityResourceId()
        repeat(MAX_PREEMPTION_ADMISSION_ATTEMPTS) {
            val admission = coordinator.acquirePlayback(
                sourceId = target.sourceId.value,
                channelId = resourceId,
                sharedSessionAvailable = target is PlaybackTarget.LiveChannel &&
                    liveDvrSessionGateway != null,
            )
            if (admission.allowed) {
                activeLiveLeaseSourceId = target.sourceId.value
                activeLiveLeaseChannelId = resourceId
                return true
            }
            if (admission.recordingIdsToFinalize.isEmpty()) return false
            val finalized = try {
                withContext(NonCancellable) {
                    onRecordingPreemptedByPlayback(admission.recordingIdsToFinalize)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!finalized) return false
            admission.recordingIdsToFinalize.forEach(coordinator::releaseRecording)
        }
        return false
    }

    private fun releaseLeaseUnlessMatching(target: PlaybackTarget) {
        if (
            activeLiveLeaseSourceId != target.sourceId.value ||
            activeLiveLeaseChannelId != target.capacityResourceId()
        ) {
            releaseLiveLease()
        }
    }

    private fun markTargetUnavailable() {
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.UNAVAILABLE,
            playWhenReady = false,
        )
    }

    private fun PlaybackTarget.capacityResourceId(): String = when (this) {
        is PlaybackTarget.LiveChannel -> channelId
        is PlaybackTarget.CatchUp -> "catchup:$programId"
        is PlaybackTarget.Movie -> "movie:$movieId"
        is PlaybackTarget.Episode -> "episode:$episodeId"
    }

    private fun applyTrackSelection(
        issueWhenUnsupported: PlaybackTrackSelectionIssue,
        result: PlaybackEngineSelectionResult,
        fallbackReason: PlaybackFallbackReason?,
    ): Boolean {
        val current = mutableState.value
        if (current.target == null) return false

        return when (result) {
            PlaybackEngineSelectionResult.APPLIED -> {
                mutableState.value = current.copy(
                    tracks = current.tracks.copy(selectionIssue = null),
                )
                true
            }

            PlaybackEngineSelectionResult.UNSUPPORTED -> {
                mutableState.value = current.copy(
                    tracks = current.tracks.copy(selectionIssue = issueWhenUnsupported),
                )
                false
            }

            PlaybackEngineSelectionResult.FAILED -> {
                mutableState.value = current.copy(
                    tracks = current.tracks.copy(
                        selectionIssue = PlaybackTrackSelectionIssue.SELECTION_FAILED,
                    ),
                )
                if (fallbackReason != null) {
                    maybeAttemptFallback(fallbackReason)
                }
                false
            }
        }
    }

    private fun onPlaybackEngineEvent(event: PlaybackEngineEvent) {
        if (event.mediaRevision != activeMediaRevision) return
        val current = mutableState.value
        if (current.target == null) return

        event.tracks?.let { engineTracks ->
            mutableState.value = mutableState.value.copy(
                tracks = mapTracks(
                    engineTracks = engineTracks,
                    priorIssue = mutableState.value.tracks.selectionIssue,
                ),
            )
        }

        when (event.readiness) {
            null -> Unit
            PlaybackEngineReadiness.PREPARING -> {
                mutableState.value = mutableState.value.copy(
                    readiness = PlaybackReadiness.PREPARING,
                )
            }

            PlaybackEngineReadiness.READY -> {
                if (current.target is PlaybackTarget.LiveChannel) {
                    liveReconnectAttempts = 0
                    liveReconnectFailurePending = false
                }
                mutableState.value = mutableState.value.copy(
                    readiness = PlaybackReadiness.PREPARED,
                    endedNaturally = false,
                )
            }

            PlaybackEngineReadiness.ENDED -> {
                if (current.target is PlaybackTarget.Library || current.target is PlaybackTarget.CatchUp) {
                    checkpointActivePersistedProgress(LibraryPlaybackProgressBoundary.COMPLETION)
                    mutableState.value = mutableState.value.copy(
                        readiness = PlaybackReadiness.PREPARED,
                        playWhenReady = false,
                        fallback = mutableState.value.fallback.copy(active = false),
                        endedNaturally = true,
                    )
                } else {
                    releaseLiveLease()
                    mutableState.value = mutableState.value.copy(
                        readiness = PlaybackReadiness.UNAVAILABLE,
                        playWhenReady = false,
                        fallback = mutableState.value.fallback.copy(active = false),
                    )
                }
            }

            PlaybackEngineReadiness.FAILED -> {
                val liveTarget = current.target as? PlaybackTarget.LiveChannel
                when (event.recoveryKind) {
                    PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
                    PlaybackEngineRecoveryKind.RETRYABLE_PROVIDER,
                    -> {
                        if (liveTarget != null) {
                            scheduleLiveReconnect(liveTarget)
                            return
                        }
                    }

                    PlaybackEngineRecoveryKind.NON_RETRYABLE_AUTH,
                    PlaybackEngineRecoveryKind.NON_RETRYABLE_SOURCE,
                    -> {
                        if (liveTarget != null) {
                            finishLiveUnavailable(liveTarget, sessionGeneration)
                            return
                        }
                    }

                    null -> Unit
                }

                val fallbackReason = when (event.failureClass) {
                    PlaybackEngineFailureClass.DECODER_OR_FORMAT ->
                        PlaybackFallbackReason.DECODER_OR_FORMAT
                    PlaybackEngineFailureClass.AUDIO ->
                        PlaybackFallbackReason.AUDIO_SELECTION
                    PlaybackEngineFailureClass.OTHER,
                    null,
                    -> PlaybackFallbackReason.OTHER
                }
                if (!maybeAttemptFallback(fallbackReason)) {
                    if (liveTarget != null) {
                        finishLiveUnavailable(liveTarget, sessionGeneration)
                        return
                    }
                    mutableState.value = mutableState.value.copy(
                        readiness = PlaybackReadiness.UNAVAILABLE,
                        playWhenReady = false,
                        fallback = mutableState.value.fallback.copy(active = false),
                    )
                }
            }
        }
    }

    private fun scheduleLiveReconnect(target: PlaybackTarget.LiveChannel) {
        val generation = sessionGeneration
        if (!isCurrentSession(target, generation)) return
        if (liveReconnectJob?.isActive == true) {
            liveReconnectFailurePending = true
            return
        }
        if (liveReconnectAttempts >= LiveReconnectPolicy.MAX_RETRIES) {
            finishLiveUnavailable(target, generation)
            return
        }

        liveReconnectAttempts += 1
        liveReconnectFailurePending = false
        resetActiveMedia()
        playbackEngine.clear()
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.PREPARING,
            tracks = PlaybackTrackSnapshot(),
            fallback = PlaybackFallbackState(),
            endedNaturally = false,
        )

        var attempt = liveReconnectAttempts
        val job = reconnectScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isCurrentSession(target, generation)) {
                    reconnectDelay(LiveReconnectPolicy.backoffMs(attempt))
                    if (!isCurrentSession(target, generation)) return@launch

                    val replaced = replaceTargetMedia(target, generation) {
                        prepareLiveMedia(target)
                    }
                    if (!replaced || !isCurrentSession(target, generation)) return@launch
                    if (!liveReconnectFailurePending) return@launch

                    liveReconnectFailurePending = false
                    if (liveReconnectAttempts >= LiveReconnectPolicy.MAX_RETRIES) {
                        finishLiveUnavailable(target, generation)
                        return@launch
                    }

                    liveReconnectAttempts += 1
                    attempt = liveReconnectAttempts
                }
            } finally {
                if (liveReconnectJob === coroutineContext[Job]) liveReconnectJob = null
            }
        }
        liveReconnectJob = job
        job.start()
    }

    private fun finishLiveUnavailable(
        target: PlaybackTarget.LiveChannel,
        generation: Long,
    ) {
        if (!isCurrentSession(target, generation)) return
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        liveReconnectFailurePending = false
        resetActiveMedia()
        playbackEngine.clear()
        releaseLiveLease()
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.UNAVAILABLE,
            playWhenReady = false,
            fallback = mutableState.value.fallback.copy(active = false),
        )
    }

    private fun beginNewSessionAttempt() {
        releaseLiveLease()
        sessionGeneration += 1L
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        liveReconnectAttempts = 0
        liveReconnectFailurePending = false
    }

    private fun isCurrentSession(target: PlaybackTarget, generation: Long): Boolean =
        sessionGeneration == generation && mutableState.value.target == target

    private fun maybeAttemptFallback(reason: PlaybackFallbackReason): Boolean {
        val fallbackMedia = activeFallbackMedia
        if (
            !PlaybackFallbackPolicy.canAttempt(
                hasFallback = fallbackMedia != null,
                alreadyAttempted = fallbackAttempted,
                reason = reason,
            )
        ) {
            return false
        }

        requireNotNull(fallbackMedia)
        fallbackAttempted = true
        activeFallbackMedia = null
        activeMediaRevision = null
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.PREPARING,
            tracks = PlaybackTrackSnapshot(),
            fallback = PlaybackFallbackState(
                attempted = true,
                active = true,
            ),
        )

        return try {
            activeMediaRevision = playbackEngine.replace(fallbackMedia)
            playbackEngine.setSpeed(mutableState.value.speed)
            true
        } catch (_: Exception) {
            activeMediaRevision = null
            playbackEngine.clear()
            mutableState.value = mutableState.value.copy(
                readiness = PlaybackReadiness.UNAVAILABLE,
                playWhenReady = false,
                fallback = PlaybackFallbackState(
                    attempted = true,
                    active = false,
                ),
            )
            true
        }
    }

    private fun mapTracks(
        engineTracks: PlaybackEngineTracks,
        priorIssue: PlaybackTrackSelectionIssue?,
    ): PlaybackTrackSnapshot {
        var audioOrdinal = 0
        var subtitleOrdinal = 0

        val mapped = engineTracks.tracks.map { track ->
            val kind = when (track.kind) {
                PlaybackEngineTrackKind.AUDIO -> {
                    audioOrdinal += 1
                    PlaybackTrackKind.AUDIO
                }
                PlaybackEngineTrackKind.SUBTITLE -> {
                    subtitleOrdinal += 1
                    PlaybackTrackKind.SUBTITLE
                }
            }
            val ordinal = when (kind) {
                PlaybackTrackKind.AUDIO -> audioOrdinal
                PlaybackTrackKind.SUBTITLE -> subtitleOrdinal
            }

            PlaybackTrackOption(
                id = track.id,
                kind = kind,
                label = PlaybackTrackLabelPolicy.label(
                    kind = kind,
                    ordinal = ordinal,
                    labelHint = track.labelHint,
                    language = track.language,
                    codec = track.codec,
                    channelCount = track.channelCount,
                    role = track.role,
                ),
                language = track.language,
                codec = track.codec,
                channelCount = track.channelCount,
                role = track.role,
                supported = track.supported,
                selected = track.selected,
            )
        }

        val audioTracks = mapped
            .filter { it.kind == PlaybackTrackKind.AUDIO }
            .let { tracks ->
                tracks.filter(PlaybackTrackOption::selected) +
                    tracks.filterNot(PlaybackTrackOption::selected)
            }
        val subtitleTracks = mapped.filter { it.kind == PlaybackTrackKind.SUBTITLE }
        return PlaybackTrackSnapshot(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackId = audioTracks.firstOrNull { it.selected }?.id,
            selectedSubtitleTrackId = subtitleTracks.firstOrNull { it.selected }?.id,
            selectionIssue = priorIssue,
        )
    }

    private fun resetActiveMedia() {
        activeMediaRevision = null
        activeFallbackMedia = null
        fallbackAttempted = false
        activeLiveDvrSessionId = null
        activeLiveDvrAnchorEpochMillis = null
    }

    private fun reattachLiveDvrMedia(
        target: PlaybackTarget.LiveChannel,
        media: PreparedPlaybackMedia,
    ): Boolean {
        if (!isCurrentSession(target, sessionGeneration)) return false
        if (media.liveDvrSessionId == null) return false
        val playWhenReady = mutableState.value.playWhenReady
        return try {
            activeFallbackMedia = null
            fallbackAttempted = false
            activeMediaRevision = playbackEngine.replace(media.primaryOnly())
            activeLiveDvrSessionId = media.liveDvrSessionId
            activeLiveDvrAnchorEpochMillis = media.liveDvrStartEpochMillis
            if (playWhenReady) playbackEngine.play() else playbackEngine.pause()
            if (mutableState.value.endedNaturally) {
                mutableState.value = mutableState.value.copy(endedNaturally = false)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeEpochAdd(epochMillis: Long, deltaMillis: Long): Long? =
        runCatching { Math.addExact(epochMillis, deltaMillis) }.getOrNull()

    private fun recoverCancelledTarget(target: PlaybackTarget, generation: Long) {
        if (!isCurrentSession(target, generation)) return
        resetActiveMedia()
        playbackEngine.clear()
        releaseLiveLease()
        mutableState.value = mutableState.value.copy(
            readiness = PlaybackReadiness.IDLE,
            playWhenReady = false,
            tracks = PlaybackTrackSnapshot(),
            fallback = PlaybackFallbackState(),
            endedNaturally = false,
        )
    }

    private fun releaseLiveLease() {
        val sourceId = activeLiveLeaseSourceId
        val channelId = activeLiveLeaseChannelId
        if (sourceId != null) {
            val handledByDvr = channelId?.let { liveDvrSessionGateway?.detachPlayback(sourceId, it) == true } ?: false
            if (!handledByDvr) liveCapacityCoordinator?.release(LiveCapacityCoordinator.PLAYBACK_LEASE_ID)
        }
        activeLiveLeaseSourceId = null
        activeLiveLeaseChannelId = null
    }

    private suspend fun prepareLiveMedia(target: PlaybackTarget.LiveChannel): PreparedPlaybackMedia? {
        val source = sourceResolver.resolve(target) ?: return null
        val providerMedia = mediaPreparer.prepare(source) ?: return null
        return if (liveDvrSessionGateway == null) {
            providerMedia
        } else {
            liveDvrSessionGateway.attachPlayback(
                sourceId = target.sourceId.value,
                channelId = target.channelId,
                media = providerMedia,
            )
        }
    }
}

internal object LiveReconnectPolicy {
    const val MAX_RETRIES = 3
    private val backoffDelaysMs = listOf(250L, 500L, 1_000L)

    fun backoffMs(retryNumber: Int): Long =
        backoffDelaysMs.getOrElse(retryNumber - 1) { backoffDelaysMs.last() }
}

private const val MAX_PREEMPTION_ADMISSION_ATTEMPTS = 3
