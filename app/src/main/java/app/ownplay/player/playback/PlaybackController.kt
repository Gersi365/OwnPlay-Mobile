package app.ownplay.player.playback

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface PlaybackEngine {
    interface Listener {
        fun onReady()
        fun onPlaying()
        fun onBuffering() = Unit
        fun onPaused()
        fun onEnded()
        fun onFailure(failure: PlaybackFailure)
    }

    fun setListener(listener: Listener?)
    fun prepare(locator: ResolvedPlaybackLocator)
    fun play()
    fun pause()
    fun currentPositionMs(): Long? = null
    fun seekTo(positionMs: Long) = Unit
    fun suspendPlayback() = stop()
    fun stop()
    fun release()
}

class PlaybackController(
    private val resolveLocator: suspend (PlaybackRequest) -> PlaybackResolutionResult,
    private val engine: PlaybackEngine,
    private val resolveOfflineLocator: suspend (PlaybackRequest) -> ResolvedPlaybackLocator? = { null },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loadingTimeoutMillis: Long = DEFAULT_LOADING_TIMEOUT_MILLIS,
    private val rebufferTimeoutMillis: Long = DEFAULT_REBUFFER_TIMEOUT_MILLIS,
    private val retryPolicy: PlaybackRetryPolicy = PlaybackRetryPolicy(),
    networkState: StateFlow<PlaybackNetworkState>? = null,
) : AutoCloseable {
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + mainDispatcher)
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val mutableResolvedOrigin = MutableStateFlow<ResolvedPlaybackOrigin?>(null)

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    val resolvedOrigin: StateFlow<ResolvedPlaybackOrigin?> = mutableResolvedOrigin.asStateFlow()

    private var resolutionJob: Job? = null
    private var timeoutJob: Job? = null
    private var bufferingTimeoutJob: Job? = null
    private var retryJob: Job? = null
    private var generation: Long = 0L
    private var preparedGeneration: Long? = null
    private var currentPlaybackUsesNetwork: Boolean? = null
    private var automaticRetryAttempt: Int = 0
    private var desiredPlayWhenReady: Boolean = true
    private var backgroundSuspendedRequest: PlaybackRequest? = null
    private var backgroundSuspendedPlayWhenReady: Boolean = false
    private var backgroundSuspendedPositionMs: Long? = null
    private var networkAvailable: Boolean =
        networkState?.value != PlaybackNetworkState.UNAVAILABLE
    @Volatile
    private var released: Boolean = false

    init {
        require(loadingTimeoutMillis > 0L) { "Loading timeout must be positive" }
        require(rebufferTimeoutMillis > 0L) { "Rebuffer timeout must be positive" }
        engine.setListener(
            object : PlaybackEngine.Listener {
                override fun onReady() {
                    if (!acceptEngineEvent()) return
                    timeoutJob?.cancel()
                    bufferingTimeoutJob?.cancel()
                    bufferingTimeoutJob = null
                    retryJob?.cancel()
                    retryJob = null
                    val prepared = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Prepared)
                    mutableState.value = if (desiredPlayWhenReady) {
                        prepared
                    } else {
                        PlaybackReducer.reduce(prepared, PlaybackEvent.Pause)
                    }
                }

                override fun onPlaying() {
                    if (!acceptEngineEvent()) return
                    bufferingTimeoutJob?.cancel()
                    bufferingTimeoutJob = null
                    mutableState.value = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Play)
                }

                override fun onBuffering() {
                    if (!acceptEngineEvent() || !desiredPlayWhenReady) return
                    val playing = mutableState.value as? PlaybackState.Playing ?: return
                    if (playing.buffering) return
                    mutableState.value = PlaybackReducer.reduce(
                        playing,
                        PlaybackEvent.Buffer,
                    )
                    scheduleRebufferTimeout()
                }

                override fun onPaused() {
                    if (!acceptEngineEvent()) return
                    bufferingTimeoutJob?.cancel()
                    bufferingTimeoutJob = null
                    mutableState.value = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Pause)
                }

                override fun onEnded() {
                    if (!acceptEngineEvent()) return
                    failCurrentAndMaybeRetry(
                        PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE),
                    )
                }

                override fun onFailure(failure: PlaybackFailure) {
                    if (!acceptEngineEvent()) return
                    failCurrentAndMaybeRetry(failure)
                }
            },
        )

        if (networkState != null) {
            scope.launch {
                networkState.collect { observed ->
                    val nowAvailable = observed == PlaybackNetworkState.AVAILABLE
                    if (released || networkAvailable == nowAvailable) return@collect
                    networkAvailable = nowAvailable
                    if (nowAvailable) {
                        handleNetworkAvailable()
                    } else {
                        handleNetworkUnavailable()
                    }
                }
            }
        }
    }

    fun start(request: PlaybackRequest) {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            clearBackgroundSuspension()
            startOnControllerDispatcher(
                request = request,
                resetRetryBudget = true,
            )
        }
    }

    fun play() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            desiredPlayWhenReady = true
            if (backgroundSuspendedRequest != null) {
                backgroundSuspendedPlayWhenReady = true
                return@launch
            }
            mutableState.value = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Play)
            engine.play()
        }
    }

    fun pause() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            desiredPlayWhenReady = false
            bufferingTimeoutJob?.cancel()
            bufferingTimeoutJob = null
            if (backgroundSuspendedRequest != null) {
                backgroundSuspendedPlayWhenReady = false
                mutableState.value = PlaybackState.Paused(backgroundSuspendedRequest!!)
                return@launch
            }
            mutableState.value = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Pause)
            engine.pause()
        }
    }

    fun suspendForBackground() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            if (backgroundSuspendedRequest != null) return@launch
            val request = when (val current = mutableState.value) {
                is PlaybackState.Loading -> current.request
                is PlaybackState.Playing -> current.request
                is PlaybackState.Paused -> current.request
                PlaybackState.Idle,
                is PlaybackState.Failed,
                -> null
            } ?: return@launch

            backgroundSuspendedRequest = request
            backgroundSuspendedPlayWhenReady = desiredPlayWhenReady
            backgroundSuspendedPositionMs = if (request.mediaKind == PlaybackMediaKind.LIVE) {
                null
            } else {
                engine.currentPositionMs()?.takeIf { it > 0L }
            }
            generation += 1
            preparedGeneration = null
            currentPlaybackUsesNetwork = null
            mutableResolvedOrigin.value = null
            resolutionJob?.cancel()
            timeoutJob?.cancel()
            bufferingTimeoutJob?.cancel()
            bufferingTimeoutJob = null
            retryJob?.cancel()
            retryJob = null
            engine.suspendPlayback()
            desiredPlayWhenReady = false
            mutableState.value = PlaybackState.Paused(request)
        }
    }

    fun resumeAfterBackground() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            val request = backgroundSuspendedRequest ?: return@launch
            val playWhenReady = backgroundSuspendedPlayWhenReady
            val positionMs = backgroundSuspendedPositionMs
            clearBackgroundSuspension()
            startOnControllerDispatcher(
                request = request,
                resetRetryBudget = false,
                playWhenReady = playWhenReady,
                initialPositionMs = positionMs,
                stopEngineBeforeStart = false,
            )
        }
    }

    fun retry() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            val failed = mutableState.value as? PlaybackState.Failed ?: return@launch
            if (!failed.failure.retryable) return@launch
            val initialPositionMs = retryPositionFor(failed.request)
            clearBackgroundSuspension()
            retryJob?.cancel()
            retryJob = null
            automaticRetryAttempt = 0
            startOnControllerDispatcher(
                request = failed.request,
                resetRetryBudget = false,
                initialPositionMs = initialPositionMs,
            )
        }
    }

    fun stop() {
        check(!released) { "PlaybackController is released" }
        scope.launch {
            clearBackgroundSuspension()
            stopOnControllerDispatcher()
        }
    }

    fun stopIfCurrent(
        sourceId: String,
        channelId: String,
        mediaKind: PlaybackMediaKind,
    ) {
        if (released) return
        scope.launch {
            val currentRequest =
                backgroundSuspendedRequest ?: mutableState.value.requestOrNull() ?: return@launch
            if (
                currentRequest.sourceId != sourceId ||
                currentRequest.channelId != channelId ||
                currentRequest.mediaKind != mediaKind
            ) {
                return@launch
            }
            clearBackgroundSuspension()
            stopOnControllerDispatcher()
        }
    }

    override fun close() {
        if (released) return
        released = true
        clearBackgroundSuspension()
        generation += 1
        preparedGeneration = null
        currentPlaybackUsesNetwork = null
        mutableResolvedOrigin.value = null
        resolutionJob?.cancel()
        timeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        retryJob?.cancel()
        retryJob = null
        engine.setListener(null)
        engine.stop()
        engine.release()
        mutableState.value = PlaybackState.Idle
        scope.cancel()
    }

    private fun startOnControllerDispatcher(
        request: PlaybackRequest,
        resetRetryBudget: Boolean,
        playWhenReady: Boolean = true,
        initialPositionMs: Long? = null,
        stopEngineBeforeStart: Boolean = true,
    ) {
        generation += 1
        val requestGeneration = generation

        resolutionJob?.cancel()
        timeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        retryJob?.cancel()
        retryJob = null
        preparedGeneration = null
        currentPlaybackUsesNetwork = null
        mutableResolvedOrigin.value = null
        if (stopEngineBeforeStart) {
            engine.stop()
        }

        if (resetRetryBudget) {
            automaticRetryAttempt = 0
        }
        desiredPlayWhenReady = playWhenReady
        mutableState.value = PlaybackReducer.reduce(
            mutableState.value,
            PlaybackEvent.Start(request),
        )

        timeoutJob = scope.launch {
            delay(loadingTimeoutMillis)
            if (
                !released &&
                requestGeneration == generation &&
                mutableState.value is PlaybackState.Loading
            ) {
                resolutionJob?.cancel()
                failCurrentAndMaybeRetry(
                    PlaybackFailure(PlaybackFailureCategory.TIMEOUT),
                )
            }
        }

        resolutionJob = scope.launch {
            val offlineLocator = try {
                withContext(ioDispatcher) {
                    resolveOfflineLocator(request)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

            ensureActive()
            if (released || requestGeneration != generation) return@launch

            if (offlineLocator != null) {
                prepareResolvedLocator(
                    locator = offlineLocator,
                    requestGeneration = requestGeneration,
                    initialPositionMs = initialPositionMs,
                )
                return@launch
            }

            currentPlaybackUsesNetwork = true
            if (!networkAvailable) {
                timeoutJob?.cancel()
                failCurrentAndMaybeRetry(
                    PlaybackFailure(PlaybackFailureCategory.NETWORK_UNAVAILABLE),
                )
                return@launch
            }

            val result = try {
                withContext(ioDispatcher) {
                    resolveLocator(request)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PlaybackResolutionResult.Failure(
                    PlaybackResolutionFailureReason.PERSISTENCE_FAILURE,
                )
            }

            ensureActive()
            if (released || requestGeneration != generation) return@launch

            when (result) {
                is PlaybackResolutionResult.Success -> {
                    prepareResolvedLocator(
                        locator = result.locator,
                        requestGeneration = requestGeneration,
                        initialPositionMs = initialPositionMs,
                    )
                }
                is PlaybackResolutionResult.Failure -> {
                    timeoutJob?.cancel()
                    failCurrentAndMaybeRetry(result.reason.toPlaybackFailure())
                }
            }
        }
    }

    private fun prepareResolvedLocator(
        locator: ResolvedPlaybackLocator,
        requestGeneration: Long,
        initialPositionMs: Long?,
    ) {
        val usesNetwork = locator.origin != ResolvedPlaybackOrigin.LOCAL_DOWNLOAD
        currentPlaybackUsesNetwork = usesNetwork
        if (usesNetwork && !networkAvailable) {
            timeoutJob?.cancel()
            failCurrentAndMaybeRetry(
                PlaybackFailure(PlaybackFailureCategory.NETWORK_UNAVAILABLE),
            )
            return
        }
        mutableResolvedOrigin.value = locator.origin
        preparedGeneration = requestGeneration
        engine.prepare(locator)
        initialPositionMs?.takeIf { it > 0L }?.let(engine::seekTo)
        if (desiredPlayWhenReady) {
            engine.play()
        } else {
            engine.pause()
        }
    }

    private fun stopOnControllerDispatcher() {
        generation += 1
        preparedGeneration = null
        currentPlaybackUsesNetwork = null
        mutableResolvedOrigin.value = null
        resolutionJob?.cancel()
        timeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        retryJob?.cancel()
        retryJob = null
        automaticRetryAttempt = 0
        engine.stop()
        mutableState.value = PlaybackReducer.reduce(mutableState.value, PlaybackEvent.Stop)
    }

    private fun clearBackgroundSuspension() {
        backgroundSuspendedRequest = null
        backgroundSuspendedPlayWhenReady = false
        backgroundSuspendedPositionMs = null
    }

    private fun retryPositionFor(request: PlaybackRequest): Long? =
        if (request.mediaKind == PlaybackMediaKind.LIVE) {
            null
        } else {
            engine.currentPositionMs()?.takeIf { it > 0L }
        }

    private fun handleNetworkUnavailable() {
        if (currentPlaybackUsesNetwork != true) return
        val request = mutableState.value.requestOrNull() ?: return
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        retryJob?.cancel()
        retryJob = null
        generation += 1
        preparedGeneration = null
        currentPlaybackUsesNetwork = null
        mutableResolvedOrigin.value = null
        resolutionJob?.cancel()
        timeoutJob?.cancel()
        engine.suspendPlayback()
        mutableState.value = PlaybackState.Failed(
            request = request,
            failure = PlaybackFailure(PlaybackFailureCategory.NETWORK_UNAVAILABLE),
        )
    }

    private fun handleNetworkAvailable() {
        val failed = mutableState.value as? PlaybackState.Failed ?: return
        if (failed.failure.category != PlaybackFailureCategory.NETWORK_UNAVAILABLE) return
        automaticRetryAttempt = 0
        scheduleAutomaticRetry(failed.request)
    }

    private fun scheduleRebufferTimeout() {
        bufferingTimeoutJob?.cancel()
        val bufferingGeneration = generation
        bufferingTimeoutJob = scope.launch {
            delay(rebufferTimeoutMillis)
            val playing = mutableState.value as? PlaybackState.Playing
            if (
                released ||
                bufferingGeneration != generation ||
                !desiredPlayWhenReady ||
                playing?.buffering != true
            ) {
                return@launch
            }
            bufferingTimeoutJob = null
            failCurrentAndMaybeRetry(
                PlaybackFailure(PlaybackFailureCategory.TIMEOUT),
            )
        }
    }

    private fun failCurrentAndMaybeRetry(failure: PlaybackFailure) {
        val request = mutableState.value.requestOrNull() ?: return
        timeoutJob?.cancel()
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
        preparedGeneration = null
        currentPlaybackUsesNetwork = null
        engine.suspendPlayback()
        mutableState.value = PlaybackState.Failed(
            request = request,
            failure = failure,
        )

        if (!failure.retryable) return
        if (
            failure.category == PlaybackFailureCategory.NETWORK_UNAVAILABLE &&
            !networkAvailable
        ) {
            return
        }
        scheduleAutomaticRetry(request)
    }

    private fun scheduleAutomaticRetry(request: PlaybackRequest) {
        if (!networkAvailable) return
        if (automaticRetryAttempt >= retryPolicy.maxAutomaticAttempts) return

        val attempt = automaticRetryAttempt + 1
        val failedGeneration = generation
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryPolicy.delayBeforeAttempt(attempt))
            if (
                released ||
                !networkAvailable ||
                failedGeneration != generation
            ) {
                return@launch
            }
            val failed = mutableState.value as? PlaybackState.Failed ?: return@launch
            if (failed.request != request || !failed.failure.retryable) return@launch

            val initialPositionMs = retryPositionFor(request)
            retryJob = null
            automaticRetryAttempt = attempt
            startOnControllerDispatcher(
                request = request,
                resetRetryBudget = false,
                initialPositionMs = initialPositionMs,
            )
        }
    }

    private fun acceptEngineEvent(): Boolean =
        !released &&
            preparedGeneration != null &&
            preparedGeneration == generation

    companion object {
        const val DEFAULT_LOADING_TIMEOUT_MILLIS: Long = 30_000L
        const val DEFAULT_REBUFFER_TIMEOUT_MILLIS: Long = 20_000L
    }
}

private fun PlaybackState.requestOrNull(): PlaybackRequest? = when (this) {
    PlaybackState.Idle -> null
    is PlaybackState.Loading -> request
    is PlaybackState.Playing -> request
    is PlaybackState.Paused -> request
    is PlaybackState.Failed -> request
}

internal fun PlaybackResolutionFailureReason.toPlaybackFailure(): PlaybackFailure {
    val category = when (this) {
        PlaybackResolutionFailureReason.CLEARTEXT_NOT_ALLOWED,
        PlaybackResolutionFailureReason.UNSUPPORTED_SOURCE_KIND,
        PlaybackResolutionFailureReason.DESCRIPTOR_INVALID,
        PlaybackResolutionFailureReason.SOURCE_LOCATOR_INVALID,
        -> PlaybackFailureCategory.UNSUPPORTED_MEDIA

        PlaybackResolutionFailureReason.SOURCE_NOT_FOUND,
        PlaybackResolutionFailureReason.SOURCE_DISABLED,
        PlaybackResolutionFailureReason.CHANNEL_NOT_FOUND,
        PlaybackResolutionFailureReason.SOURCE_CHANNEL_MISMATCH,
        PlaybackResolutionFailureReason.CHANNEL_REMOVED,
        PlaybackResolutionFailureReason.MOVIE_NOT_FOUND,
        PlaybackResolutionFailureReason.SOURCE_MOVIE_MISMATCH,
        PlaybackResolutionFailureReason.DESCRIPTOR_REFERENCE_INVALID,
        PlaybackResolutionFailureReason.DESCRIPTOR_NOT_FOUND,
        PlaybackResolutionFailureReason.SOURCE_LOCATOR_REFERENCE_INVALID,
        PlaybackResolutionFailureReason.SOURCE_LOCATOR_NOT_FOUND,
        -> PlaybackFailureCategory.STREAM_UNAVAILABLE

        PlaybackResolutionFailureReason.SECURE_STORE_FAILURE,
        PlaybackResolutionFailureReason.CREDENTIAL_REFERENCE_MISSING,
        PlaybackResolutionFailureReason.CREDENTIAL_REFERENCE_INVALID,
        PlaybackResolutionFailureReason.CREDENTIALS_NOT_FOUND,
        PlaybackResolutionFailureReason.CREDENTIALS_INVALID,
        PlaybackResolutionFailureReason.CREDENTIAL_STORE_FAILURE,
        PlaybackResolutionFailureReason.PERSISTENCE_FAILURE,
        -> PlaybackFailureCategory.UNKNOWN
    }
    return PlaybackFailure(category)
}
