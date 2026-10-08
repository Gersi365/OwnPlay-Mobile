package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.withCrossSchemeRedirectsDisabled
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingTransitionPolicy
import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureStages
import app.ownplay.mobile.feature.playback.data.SourceBackedLivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.domain.LiveDvrSessionGateway
import app.ownplay.mobile.feature.playback.domain.LivePlaybackMediaPreparer
import app.ownplay.mobile.sources.domain.SourceId
import java.io.IOException
import java.net.SocketTimeoutException
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class LiveRecordingFailureCategory {
    AUTHORIZATION,
    UNSUPPORTED_FORMAT,
    STORAGE,
    SOURCE_UNAVAILABLE,
    NETWORK,
    UNKNOWN,
}

internal enum class LiveRecordingCaptureStage {
    SOURCE_HTTP_RESPONSE,
    DIRECT_BODY,
    DIRECT_TS_VALIDATION,
    HLS_PLAYLIST_FETCH,
    HLS_PLAYLIST_VALIDATION,
    HLS_SEGMENT_FETCH,
    HLS_SEGMENT_VALIDATION,
    RETAINED_STORE,
    RECORDING_SINK,
    STORAGE_HEADROOM,
    CAPACITY_ADMISSION,
    UNKNOWN,
}

internal data class LiveRecordingFailurePresentation(
    val category: LiveRecordingFailureCategory,
    val safeMessage: String,
    val stage: LiveRecordingCaptureStage = LiveRecordingCaptureStage.UNKNOWN,
)

internal class LiveRecordingCaptureFailureException(
    val category: LiveRecordingFailureCategory,
    val safeMessage: String,
    cause: Throwable? = null,
    val stage: LiveRecordingCaptureStage = LiveRecordingCaptureStage.UNKNOWN,
) : IOException(safeMessage, cause)

internal object LiveRecordingFailurePolicy {
    fun classify(error: Throwable): LiveRecordingFailurePresentation = when (error) {
        is LiveRecordingCaptureFailureException ->
            LiveRecordingFailurePresentation(error.category, error.safeMessage, error.stage)
        is SecurityException ->
            LiveRecordingFailurePresentation(
                LiveRecordingFailureCategory.AUTHORIZATION,
                "OwnPlay does not have permission to access the recording source or destination.",
            )
        is SocketTimeoutException ->
            LiveRecordingFailurePresentation(
                LiveRecordingFailureCategory.NETWORK,
                "The live stream timed out while recording.",
            )
        is IOException ->
            LiveRecordingFailurePresentation(
                LiveRecordingFailureCategory.NETWORK,
                "The live stream or network connection ended before recording completed.",
            )
        else ->
            LiveRecordingFailurePresentation(
                LiveRecordingFailureCategory.UNKNOWN,
                "The recording could not be completed.",
            )
    }
}

private fun Throwable.asCaptureFailure(stage: LiveRecordingCaptureStage): LiveRecordingCaptureFailureException =
    this as? LiveRecordingCaptureFailureException ?: LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.NETWORK,
        "The live provider connection failed.",
        this,
        stage,
    )

private fun elapsedMillis(startedAtNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNanos).coerceAtLeast(0L))

private fun recordingHttpFailure(
    code: Int,
    stage: LiveRecordingCaptureStage = LiveRecordingCaptureStage.SOURCE_HTTP_RESPONSE,
): LiveRecordingCaptureFailureException = when (code) {
    401, 403 -> LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.AUTHORIZATION,
        "The provider did not authorize recording access.",
        stage = stage,
    )
    404, 410 -> LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
        "The live recording source is unavailable.",
        stage = stage,
    )
    else -> LiveRecordingCaptureFailureException(
        LiveRecordingFailureCategory.NETWORK,
        "The provider connection failed while recording.",
        stage = stage,
    )
}

private fun LiveRecordingFailureCategory.failureCode(): String = when (this) {
    LiveRecordingFailureCategory.AUTHORIZATION -> LiveRecordingFailureCodes.AUTHORIZATION
    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT -> LiveRecordingFailureCodes.UNSUPPORTED_FORMAT
    LiveRecordingFailureCategory.STORAGE -> LiveRecordingFailureCodes.STORAGE
    LiveRecordingFailureCategory.SOURCE_UNAVAILABLE -> LiveRecordingFailureCodes.SOURCE_UNAVAILABLE
    LiveRecordingFailureCategory.NETWORK -> LiveRecordingFailureCodes.NETWORK
    LiveRecordingFailureCategory.UNKNOWN -> LiveRecordingFailureCodes.UNKNOWN
}

private fun String.safeMessage(): String = when (this) {
    LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK ->
        "A provider stream is using the available connection. Playback continues; recording was not started."
    LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING ->
        "Another recording is using the available Live connection."
    else -> "The recording could not start because Live capacity was unavailable."
}

internal class LiveRecordingCaptureExecutor(
    private val repository: LiveRecordingRepository,
    private val sourceResolver: SourceBackedLivePlaybackSourceResolver,
    private val mediaPreparer: LivePlaybackMediaPreparer,
    private val downloadStorage: app.ownplay.mobile.downloads.data.DownloadStorage,
    private val downloadPreferencesRepository: DownloadPreferencesRepository,
    private val liveDvrSessionGateway: LiveDvrSessionGateway,
    private val liveCapacityCoordinator: LiveCapacityCoordinator? = null,
) {
    private val finalizationCommitter = LiveRecordingFinalizationCommitter(repository, downloadStorage)
    private val recoveryCoordinator = LiveRecordingRecoveryCoordinator(repository, downloadStorage)

    suspend fun recoverInterruptedRecordings(interrupted: List<LiveRecording>) =
        recoveryCoordinator.recover(interrupted)

    private suspend fun persistRecoveredRecording(
        recording: LiveRecording,
        descriptor: String,
        reference: String,
        size: Long,
        partial: Boolean,
        processInterrupted: Boolean,
        partialWasUserStopped: Boolean = recording.failureReasonCode == LiveRecordingFailureCodes.USER_STOPPED ||
            recording.failureReason == "STOPPED_BY_USER",
        pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput? = null,
    ): Boolean = finalizationCommitter.commitPublished(
        recording = recording,
        descriptor = descriptor,
        reference = reference,
        size = size,
        partial = partial,
        processInterrupted = processInterrupted,
        partialWasUserStopped = partialWasUserStopped,
        pending = pending,
    )

    suspend fun execute(recordingId: String): Boolean {
        val scheduled = repository.get(recordingId) ?: return false
        if (scheduled.status != LiveRecordingStatus.SCHEDULED) return false
        val now = System.currentTimeMillis() / 1_000L
        if (scheduled.endEpochSeconds <= now) {
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "The scheduled recording window has passed.",
                    failureReason = LiveRecordingFailureCodes.START_WINDOW_MISSED,
                    failureReasonCode = LiveRecordingFailureCodes.START_WINDOW_MISSED,
                    failureStage = LiveRecordingFailureStages.DUE_START,
                    failureAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return false
        }
        val captureRequested = System.currentTimeMillis() / 1_000L
        val admission = liveCapacityCoordinator?.acquireRecording(
            sourceId = scheduled.sourceId,
            recordingId = recordingId,
            channelId = scheduled.channelId,
            scheduledAtEpochMs = scheduled.scheduledAtEpochMs ?: captureRequested * 1_000L,
            sharedSessionAvailable = true,
        )
        if (admission?.allowed == false) {
            val reason = admission.failureReasonCode ?: LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = reason.safeMessage(),
                    failureReason = reason,
                    failureReasonCode = reason,
                    failureStage = LiveRecordingFailureStages.CAPACITY_ADMISSION,
                    failureAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return false
        }
        val activeRecording = scheduled.copy(
                status = LiveRecordingStatus.STARTING,
                captureRequestedEpochSeconds = captureRequested,
                actualStartEpochSeconds = null,
                deadlineEpochSeconds = scheduled.endEpochSeconds,
                progressBytes = 0L,
                finalizationState = LiveRecordingFinalizationState.NOT_STARTED,
                safeError = null,
                failureReason = null,
                failureReasonCode = null,
                failureStage = null,
                failureAtEpochMs = null,
            )
        var transitionedToStarting = false
        if (!repository.update(recordingId) { current ->
                if (current.status != LiveRecordingStatus.SCHEDULED) {
                    current
                } else {
                    transitionedToStarting = true
                    activeRecording
                }
            } || !transitionedToStarting
        ) {
            liveCapacityCoordinator?.releaseRecording(recordingId)
            return false
        }

        var pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput? = null
        var validatingOutput: TransportStreamValidatingOutputStream? = null
        var pendingDescriptor: String? = null
        var publishAttempted = false
        return try {
            try {
            val source = sourceResolver.resolve(
                PlaybackTarget.LiveChannel(
                    sourceId = SourceId(scheduled.sourceId),
                    channelId = scheduled.channelId,
                ),
            ) ?: throw LiveRecordingCaptureFailureException(
                LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
                "The live source is unavailable.",
            )
            val prepared = mediaPreparer.prepare(source) ?: throw LiveRecordingCaptureFailureException(
                LiveRecordingFailureCategory.SOURCE_UNAVAILABLE,
                "The live source is unavailable.",
            )
            val selected = selectLiveMedia(prepared.uri, prepared.mimeType, prepared.fallback?.uri, prepared.fallback?.mimeType)
            val id = DownloadId("recording-" + scheduled.recordingId)
            val preferences = downloadPreferencesRepository.current()
            val output = ResolvedDownloadMedia(
                uri = selected.first,
                extension = "ts",
                displayName = recordingFileName(scheduled.title, scheduled.startEpochSeconds, scheduled.recordingId),
                relativeDirectories = listOf("Recordings"),
            )
            pending = downloadStorage.openPendingAtDestination(
                downloadId = id,
                media = output,
                destinationRelativePath = preferences.destinationRelativePath,
            ) ?: throw LiveRecordingCaptureFailureException(
                LiveRecordingFailureCategory.STORAGE,
                "OwnPlay could not create the recording file.",
            )
            pendingDescriptor = downloadStorage.describePending(pending!!)
            if (!repository.update(recordingId) { it.copy(pendingOutputDescriptor = pendingDescriptor) }) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.STORAGE,
                    "OwnPlay could not save the recording state.",
                )
            }
            validatingOutput = TransportStreamValidatingOutputStream(pending!!.outputStream) {
                if (!repository.update(recordingId) { current ->
                        LiveRecordingTransitionPolicy.firstValidatedMedia(current, System.currentTimeMillis() / 1_000L)
                    }
                ) throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.STORAGE,
                    "OwnPlay could not save the recording state.",
                )
            }

            withContext(Dispatchers.IO) {
                liveDvrSessionGateway.captureForRecording(
                    sourceId = scheduled.sourceId,
                    channelId = scheduled.channelId,
                    recordingId = recordingId,
                    endEpochMillis = scheduled.deadlineEpochSeconds * 1_000L,
                    media = prepared,
                    output = validatingOutput!!,
                    onProgress = { bytes ->
                        val current = repository.get(recordingId) ?: return@captureForRecording
                        if (bytes - current.progressBytes >= PROGRESS_BYTES_STEP) {
                            repository.update(recordingId) { LiveRecordingTransitionPolicy.progress(it, bytes) }
                        }
                    },
                )
            }
            validatingOutput!!.ensureValid()
            pending!!.outputStream.flush()
            pending!!.outputStream.close()
            val size = downloadStorage.verifiedSize(pending!!)
                ?: throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.STORAGE,
                    "The recording file became unavailable.",
                )
            if (!isPlausibleTransportStream(size) || validatingOutput?.bytesWritten != size) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.NETWORK,
                    "The live stream ended before a complete recording could be saved.",
                )
            }
            val latestBeforePublish = repository.get(recordingId) ?: activeRecording
            if (!repository.put(
                    latestBeforePublish.copy(
                        status = LiveRecordingStatus.FINALIZING,
                        finalizationState = LiveRecordingFinalizationState.COMPLETED_FINALIZING,
                        pendingOutputDescriptor = pendingDescriptor,
                    ),
                )
            ) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.STORAGE,
                    "OwnPlay could not save the recording state.",
                )
            }
            publishAttempted = true
            val reference = downloadStorage.publish(pending!!)
                ?: throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.STORAGE,
                    "OwnPlay could not finalize the recording file.",
                )
            if (!persistRecoveredRecording(
                    recording = repository.get(recordingId) ?: latestBeforePublish,
                    descriptor = pendingDescriptor!!,
                    reference = reference,
                    size = size,
                    partial = false,
                    processInterrupted = false,
                    pending = pending,
            )) {
                repository.update(recordingId) { current ->
                    current.copy(
                        failureReason = current.failureReason ?: LiveRecordingFailureCodes.STORAGE,
                        failureReasonCode = current.failureReasonCode ?: LiveRecordingFailureCodes.STORAGE,
                        failureStage = current.failureStage ?: LiveRecordingFailureStages.FINALIZATION,
                        failureAtEpochMs = current.failureAtEpochMs ?: System.currentTimeMillis(),
                        safeError = current.safeError ?: "OwnPlay could not commit the saved recording details.",
                    )
                }
                return false
            }
            pending = null
            true
        } catch (cancelled: CancellationException) {
            if (publishAttempted) {
                withContext(NonCancellable) {
                    repository.update(recordingId) { current ->
                        current.copy(
                            failureReason = current.failureReason ?: LiveRecordingFailureCodes.PROCESS_INTERRUPTED,
                            failureReasonCode = current.failureReasonCode ?: LiveRecordingFailureCodes.PROCESS_INTERRUPTED,
                            failureStage = current.failureStage ?: LiveRecordingFailureStages.FINALIZATION,
                            failureAtEpochMs = current.failureAtEpochMs ?: System.currentTimeMillis(),
                        )
                    }
                }
                throw cancelled
            }
            val current = repository.get(recordingId) ?: scheduled
            withContext(NonCancellable) {
                pending?.let { runCatching { it.outputStream.close() } }
                if (!publishPartialIfSafe(current, pending, validatingOutput)) {
                    val discarded = discardPendingSafely(pending)
                    if (discarded) {
                        val reason = current.failureReasonCode ?: LiveRecordingFailureCodes.UNKNOWN
                        repository.put(
                            current.copy(
                                status = LiveRecordingStatus.FAILED,
                                finalizationState = LiveRecordingFinalizationState.DISCARDED,
                                pendingOutputDescriptor = null,
                                failureReason = current.failureReason ?: reason,
                                failureReasonCode = reason,
                                failureStage = current.failureStage ?: LiveRecordingFailureStages.CAPTURE,
                                failureAtEpochMs = current.failureAtEpochMs ?: System.currentTimeMillis(),
                                safeError = when (reason) {
                                    LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING ->
                                        "Playback took priority; no safe partial recording could be saved."
                                    LiveRecordingFailureCodes.USER_STOPPED ->
                                        "Recording stopped; no safe partial file could be saved."
                                    LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT ->
                                        "Android's recording service time limit was reached; no safe partial file could be saved."
                                    else -> "Recording stopped before a safe partial file could be saved."
                                },
                            ),
                        )
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val current = repository.get(recordingId) ?: scheduled
            val failure = LiveRecordingFailurePolicy.classify(error)
            val reason = failure.category.failureCode()
            if (publishAttempted) {
                repository.update(recordingId) { latest ->
                    latest.copy(
                        failureReason = latest.failureReason ?: reason,
                        failureReasonCode = latest.failureReasonCode ?: reason,
                        failureStage = latest.failureStage ?: LiveRecordingFailureStages.FINALIZATION,
                        failureAtEpochMs = latest.failureAtEpochMs ?: System.currentTimeMillis(),
                        safeError = latest.safeError ?: failure.safeMessage,
                    )
                }
                return false
            }
            val diagnosed = current.copy(
                failureReason = current.failureReason ?: reason,
                failureReasonCode = current.failureReasonCode ?: reason,
                failureStage = current.failureStage ?: LiveRecordingFailureStages.CAPTURE,
                failureAtEpochMs = current.failureAtEpochMs ?: System.currentTimeMillis(),
                safeError = current.safeError ?: failure.safeMessage,
            )
            pending?.let { runCatching { it.outputStream.close() } }
            if (!publishPartialIfSafe(diagnosed, pending, validatingOutput)) {
                if (discardPendingSafely(pending)) {
                    repository.put(
                        diagnosed.copy(
                            status = LiveRecordingStatus.FAILED,
                            finalizationState = LiveRecordingFinalizationState.DISCARDED,
                            pendingOutputDescriptor = null,
                            safeError = failure.safeMessage,
                        ),
                    )
                }
            }
            false
            }
        } finally {
            val dvrOwnedLease = liveDvrSessionGateway.completeRecording(recordingId)
            if (!dvrOwnedLease) liveCapacityCoordinator?.releaseRecording(recordingId)
        }
    }

    private suspend fun publishPartialIfSafe(
        current: LiveRecording,
        pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput?,
        validatingOutput: TransportStreamValidatingOutputStream?,
    ): Boolean {
        pending ?: return false
        if (validatingOutput?.isValid != true) return false
        val size = downloadStorage.verifiedSize(pending) ?: return false
        if (!isPlausibleTransportStream(size) || validatingOutput.bytesWritten != size) return false
        val descriptor = downloadStorage.describePending(pending)
        val intent = current.copy(
            status = LiveRecordingStatus.FINALIZING,
            finalizationState = LiveRecordingFinalizationState.PARTIAL_FINALIZING,
            pendingOutputDescriptor = descriptor,
        )
        if (!repository.put(intent)) return false
        val reference = try {
            downloadStorage.publish(pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The persisted partial-finalization intent lets restart reconcile either storage state.
            return true
        } ?: return true
        persistRecoveredRecording(
            recording = intent,
            descriptor = descriptor,
            reference = reference,
            size = size,
            partial = true,
            processInterrupted = current.failureReasonCode == LiveRecordingFailureCodes.PROCESS_INTERRUPTED ||
                current.failureReason == LiveRecordingFailureCodes.PROCESS_INTERRUPTED,
            partialWasUserStopped = current.failureReasonCode == LiveRecordingFailureCodes.USER_STOPPED ||
                current.failureReason == "STOPPED_BY_USER",
            pending = pending,
        )
        return true
    }

    private fun isPlausibleTransportStream(size: Long): Boolean =
        size >= MIN_PARTIAL_TS_BYTES && size % TS_PACKET_BYTES == 0L

    private suspend fun discardPendingSafely(
        pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput?,
    ): Boolean {
        pending ?: return true
        return try {
            downloadStorage.discard(pending)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun selectLiveMedia(
        primaryUri: String,
        primaryMimeType: String?,
        fallbackUri: String?,
        fallbackMimeType: String?,
    ): Pair<String, Boolean> {
        if (LiveRecordingCaptureClient.isHls(primaryUri, primaryMimeType)) {
            return primaryUri to true
        }
        if (
            fallbackUri != null &&
            LiveRecordingCaptureClient.isHls(fallbackUri, fallbackMimeType)
        ) {
            return fallbackUri to true
        }
        if (isDirectTransportStream(primaryUri, primaryMimeType)) return primaryUri to false
        if (fallbackUri != null && isDirectTransportStream(fallbackUri, fallbackMimeType)) {
            return fallbackUri to false
        }
        throw LiveRecordingCaptureFailureException(
            LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
            "This live stream format cannot be recorded safely.",
        )
    }

    private fun isDirectTransportStream(uri: String, mimeType: String?): Boolean {
        if (mimeType?.contains("mp2t", ignoreCase = true) == true) return true
        val path = uri.substringBefore('?').substringAfterLast('/')
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isBlank() || extension.equals("ts", ignoreCase = true)
    }

    private fun recordingFileName(title: String, startEpochSeconds: Long, recordingId: String): String {
        val safeTitle = title
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            .trim()
            .take(72)
            .ifBlank { "Live recording" }
        val jobSuffix = java.security.MessageDigest.getInstance("SHA-256")
            .digest(recordingId.toByteArray(Charsets.UTF_8)).take(6)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return safeTitle + "-" + startEpochSeconds + "-" + jobSuffix + ".ts"
    }

    private companion object {
        const val TS_PACKET_BYTES = 188L
        const val MIN_PARTIAL_TS_BYTES = TS_PACKET_BYTES * 64L
        const val PROGRESS_BYTES_STEP = 512L * 1024L
    }
}

internal class LiveRecordingCaptureClient(
    playlistClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    streamClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : LiveDvrIngressClient {
    private val playlistClient = playlistClient.withCrossSchemeRedirectsDisabled()
    private val streamClient = streamClient.withCrossSchemeRedirectsDisabled()

    override suspend fun capture(
        uri: String,
        isHls: Boolean,
        startEpochMillis: Long,
        endEpochMillis: Long,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit,
    ) {
        if (isHls) {
            captureHls(uri, startEpochMillis, endEpochMillis, output, onProgress)
        } else {
            captureDirect(uri, endEpochMillis, output, onProgress)
        }
    }

    private suspend fun captureDirect(
        uri: String,
        endEpochMillis: Long,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit,
    ) =
        coroutineScope {
            val request = Request.Builder().url(uri).build()
            val call = streamClient.newCall(request)
            val captureStartedAtNanos = System.nanoTime()
            val bytes = AtomicLong(0L)
            val reader = async(Dispatchers.IO) {
                call.execute().use { response ->
                    LiveDvrDiagnostics.record(
                        event = LiveDvrDiagnosticEvent.DIRECT_RESPONSE,
                        stage = LiveRecordingCaptureStage.SOURCE_HTTP_RESPONSE,
                        statusCode = response.code,
                        mediaType = LiveDvrDiagnostics.mediaType(response.body?.contentType()),
                        redirectClass = LiveDvrDiagnostics.redirectClass(request.url, response.request.url),
                        durationMs = elapsedMillis(captureStartedAtNanos),
                    )
                    if (!response.isSuccessful) throw recordingHttpFailure(response.code)
                    val body = response.body ?: throw LiveRecordingCaptureFailureException(
                        LiveRecordingFailureCategory.NETWORK,
                        "The live stream response did not contain a body.",
                        stage = LiveRecordingCaptureStage.DIRECT_BODY,
                    )
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (System.currentTimeMillis() < endEpochMillis) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            onProgress(bytes.addAndGet(count.toLong()))
                        }
                    }
                }
            }
            reader.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            val stopAtEnd = launch {
                delay((endEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L))
                call.cancel()
            }
            try {
                reader.await()
            } catch (failure: IOException) {
                if (System.currentTimeMillis() < endEpochMillis || bytes.get() == 0L) {
                    throw failure.asCaptureFailure(LiveRecordingCaptureStage.DIRECT_BODY)
                }
            } finally {
                stopAtEnd.cancel()
                call.cancel()
            }
            if (bytes.get() == 0L) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.NETWORK,
                    "The live stream returned no media bytes.",
                    stage = LiveRecordingCaptureStage.DIRECT_BODY,
                )
            }
            if (System.currentTimeMillis() < endEpochMillis) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.NETWORK,
                    "The live stream ended before the selected program.",
                    stage = LiveRecordingCaptureStage.DIRECT_BODY,
                )
            }
        }

    private suspend fun captureHls(
        uri: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit,
    ) {
        var playlistUri = uri
        var initial = true
        var completedPlaylist = false
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        while (System.currentTimeMillis() < endEpochMillis && !completedPlaylist) {
            val fetchedPlaylist = fetchText(playlistUri)
            playlistUri = fetchedPlaylist.second
            val playlist = HlsTransportStreamPlaylistParser.parse(playlistUri, fetchedPlaylist.first)
            if (playlist.variants.isNotEmpty()) {
                playlistUri = playlist.variants.maxByOrNull { it.bandwidth }?.uri
                    ?: throw LiveRecordingCaptureFailureException(
                        LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                        "The provider playlist has no usable variant.",
                        stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                    )
                initial = true
                continue
            }
            val hasProgramTime = playlist.segments.any { it.programDateTimeEpochMillis != null }
            if (
                !playlist.endList && !hasProgramTime &&
                playlist.segments.any { it.mediaSequence == null }
            ) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
            }
            val selectedSegments = when {
                initial && hasProgramTime -> playlist.segments.filter { segment ->
                    val start = segment.programDateTimeEpochMillis ?: return@filter false
                    start + (segment.durationSeconds * 1_000.0).toLong() > startEpochMillis &&
                        start < endEpochMillis
                }
                initial && playlist.endList -> playlist.segments
                initial -> emptyList()
                else -> playlist.segments.filterNot { it.identity in seen }.filter { segment ->
                    val start = segment.programDateTimeEpochMillis
                    start == null ||
                        (start < endEpochMillis &&
                            start + (segment.durationSeconds * 1_000.0).toLong() > startEpochMillis)
                }
            }
            if (initial && playlist.endList && !hasProgramTime) {
                throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
            }
            if (initial) {
                val selectedIdentities = selectedSegments.mapTo(mutableSetOf()) { it.identity }
                playlist.segments
                    .asSequence()
                    .map(HlsTransportStreamSegment::identity)
                    .filterNot(selectedIdentities::contains)
                    .forEach { seen.add(it) }
            }
            for (segment in selectedSegments) {
                if (!seen.add(segment.identity)) continue
                if (!segment.uri.substringBefore('?').endsWith(".ts", ignoreCase = true)) {
                    throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
                }
                val boundaryOutput = output as? LiveDvrSegmentBoundaryOutput
                    ?: throw LiveRecordingCaptureFailureException(
                        LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                        "HLS segments cannot be written without transactional boundaries.",
                        stage = LiveRecordingCaptureStage.HLS_SEGMENT_VALIDATION,
                    )
                val accepted = boundaryOutput.beginSegment(
                    LiveDvrIngressSegment(
                        identity = segment.identity,
                        durationMs = (segment.durationSeconds * 1_000.0).toLong().coerceAtLeast(1L),
                        programTimeEpochMs = segment.programDateTimeEpochMillis,
                        discontinuitySequence = segment.discontinuitySequence,
                    ),
                )
                if (!accepted) continue
                var completedSegment = false
                var segmentBytes = 0L
                try {
                    segmentBytes = fetchSegment(segment.uri, output)
                    completedSegment = true
                } finally {
                    boundaryOutput.endSegment(completedSegment)
                }
                totalBytes += segmentBytes
                onProgress(totalBytes)
            }
            initial = false
            completedPlaylist = playlist.endList
            if (!completedPlaylist && System.currentTimeMillis() < endEpochMillis) {
                val wait = (playlist.targetDurationSeconds * 500.0)
                    .toLong()
                    .coerceIn(500L, 5_000L)
                delay(wait)
            }
        }
        if (totalBytes <= 0L) throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
    }

    private suspend fun fetchText(uri: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(uri).build()
        val call = playlistClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
        try {
            val startedAtNanos = System.nanoTime()
            call.execute().use { response ->
                LiveDvrDiagnostics.record(
                    event = LiveDvrDiagnosticEvent.HLS_PLAYLIST_RESPONSE,
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_FETCH,
                    statusCode = response.code,
                    mediaType = LiveDvrDiagnostics.mediaType(response.body?.contentType()),
                    redirectClass = LiveDvrDiagnostics.redirectClass(request.url, response.request.url),
                    durationMs = elapsedMillis(startedAtNanos),
                )
                if (!response.isSuccessful) {
                    throw recordingHttpFailure(response.code, LiveRecordingCaptureStage.HLS_PLAYLIST_FETCH)
                }
                val text = response.body?.string() ?: throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.NETWORK,
                    "The provider playlist was empty.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_FETCH,
                )
                text to response.request.url.toString()
            }
        } catch (failure: IOException) {
            throw failure.asCaptureFailure(LiveRecordingCaptureStage.HLS_PLAYLIST_FETCH)
        } finally {
            cancellation?.dispose()
        }
    }

    private suspend fun fetchSegment(uri: String, output: OutputStream): Long =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(uri).build()
            val call = playlistClient.newCall(request)
            val cancellation = currentCoroutineContext()[Job]
                ?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
            try {
                val startedAtNanos = System.nanoTime()
                call.execute().use { response ->
                    LiveDvrDiagnostics.record(
                        event = LiveDvrDiagnosticEvent.HLS_SEGMENT_RESPONSE,
                        stage = LiveRecordingCaptureStage.HLS_SEGMENT_FETCH,
                        statusCode = response.code,
                        mediaType = LiveDvrDiagnostics.mediaType(response.body?.contentType()),
                        redirectClass = LiveDvrDiagnostics.redirectClass(request.url, response.request.url),
                        durationMs = elapsedMillis(startedAtNanos),
                    )
                    if (!response.isSuccessful) {
                        throw recordingHttpFailure(response.code, LiveRecordingCaptureStage.HLS_SEGMENT_FETCH)
                    }
                    val body = response.body ?: throw LiveRecordingCaptureFailureException(
                        LiveRecordingFailureCategory.NETWORK,
                        "The HLS segment response did not contain a body.",
                        stage = LiveRecordingCaptureStage.HLS_SEGMENT_FETCH,
                    )
                    var bytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                        }
                    }
                    LiveDvrDiagnostics.record(
                        event = LiveDvrDiagnosticEvent.HLS_SEGMENT_BODY,
                        stage = LiveRecordingCaptureStage.HLS_SEGMENT_FETCH,
                        incomingBytes = bytes,
                        durationMs = elapsedMillis(startedAtNanos),
                    )
                    bytes
                }
            } catch (failure: IOException) {
                throw failure.asCaptureFailure(LiveRecordingCaptureStage.HLS_SEGMENT_FETCH)
            } finally {
                cancellation?.dispose()
            }
        }

    companion object {
        fun isHls(uri: String, mimeType: String?): Boolean =
            uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                mimeType?.contains("mpegurl", ignoreCase = true) == true
    }
}

internal data class HlsTransportStreamVariant(val bandwidth: Long, val uri: String)

internal data class HlsTransportStreamSegment(
    val uri: String,
    val durationSeconds: Double,
    val programDateTimeEpochMillis: Long?,
    val mediaSequence: Long? = null,
    val discontinuitySequence: Long = 0L,
) {
    val identity: String
        get() = mediaSequence?.let { "sequence:$discontinuitySequence:$it" }
            ?: programDateTimeEpochMillis?.let { "time:$it" }
            ?: uri.substringBefore('#').substringBefore('?')
}

internal data class ParsedHlsTransportStreamPlaylist(
    val variants: List<HlsTransportStreamVariant>,
    val segments: List<HlsTransportStreamSegment>,
    val targetDurationSeconds: Double,
    val endList: Boolean,
)

internal object HlsTransportStreamPlaylistParser {
    fun parse(playlistUri: String, text: String): ParsedHlsTransportStreamPlaylist {
        if (!text.lineSequence().firstOrNull()?.trim().equals("#EXTM3U", ignoreCase = true)) {
            throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
        }
        val variants = mutableListOf<HlsTransportStreamVariant>()
        val segments = mutableListOf<HlsTransportStreamSegment>()
        val lines = text.lineSequence().map(String::trim).toList()
        var pendingBandwidth: Long? = null
        var pendingDuration: Double? = null
        var nextProgramTime: Long? = null
        var nextMediaSequence: Long? = null
        var discontinuitySequence = 0L
        var pendingDiscontinuity = false
        var targetDuration = 4.0
        var endList = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) &&
                    !line.contains("METHOD=NONE", ignoreCase = true) ->
                    throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) ->
                    throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) ->
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) ->
                    targetDuration = line.substringAfter(':').toDoubleOrNull()?.coerceAtLeast(0.5) ?: 4.0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) ->
                    nextMediaSequence = line.substringAfter(':').toLongOrNull()
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:", ignoreCase = true) ->
                    discontinuitySequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) ->
                    pendingDiscontinuity = true
                line.startsWith("#EXTINF:", ignoreCase = true) ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:", ignoreCase = true) ->
                    nextProgramTime = runCatching {
                        Instant.parse(line.substringAfter(':')).toEpochMilli()
                    }.getOrNull()
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                line.isNotBlank() && !line.startsWith("#") -> {
                    val resolved = runCatching { URI(playlistUri).resolve(line).toString() }
                        .getOrElse { throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                ) }
                    if (pendingBandwidth != null) {
                        variants += HlsTransportStreamVariant(pendingBandwidth!!, resolved)
                        pendingBandwidth = null
                    } else {
                        val duration = pendingDuration ?: throw LiveRecordingCaptureFailureException(
                    LiveRecordingFailureCategory.UNSUPPORTED_FORMAT,
                    "This HLS stream cannot be recorded safely.",
                    stage = LiveRecordingCaptureStage.HLS_PLAYLIST_VALIDATION,
                )
                        if (pendingDiscontinuity) {
                            discontinuitySequence += 1L
                            pendingDiscontinuity = false
                        }
                        segments += HlsTransportStreamSegment(
                            uri = resolved,
                            durationSeconds = duration,
                            programDateTimeEpochMillis = nextProgramTime,
                            mediaSequence = nextMediaSequence,
                            discontinuitySequence = discontinuitySequence,
                        )
                        nextProgramTime = nextProgramTime?.plus((duration * 1_000.0).toLong())
                        nextMediaSequence = nextMediaSequence?.plus(1L)
                        pendingDuration = null
                    }
                }
            }
        }
        return ParsedHlsTransportStreamPlaylist(variants, segments, targetDuration, endList)
    }
}

internal class TransportStreamValidatingOutputStream(
    private val delegate: OutputStream,
    private val onFirstValidatedMedia: () -> Unit = {},
) : OutputStream() {
    private var nextPacketSyncOffset = 0L
    var bytesWritten: Long = 0L
        private set
    var isValid: Boolean = true
        private set
    private var firstMediaReported = false

    override fun write(value: Int) {
        val packetOffset = bytesWritten
        if (packetOffset == nextPacketSyncOffset) {
            if ((value and 0xff) != SYNC_BYTE) isValid = false
            nextPacketSyncOffset += TS_PACKET_BYTES
        }
        delegate.write(value)
        bytesWritten += 1L
        reportFirstMediaIfValidated()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        var absolute = bytesWritten
        val end = bytesWritten + length
        while (nextPacketSyncOffset < end) {
            if ((buffer[offset + (nextPacketSyncOffset - absolute).toInt()].toInt() and 0xff) != SYNC_BYTE) {
                isValid = false
            }
            nextPacketSyncOffset += TS_PACKET_BYTES
        }
        delegate.write(buffer, offset, length)
        bytesWritten += length
        reportFirstMediaIfValidated()
    }

    private fun reportFirstMediaIfValidated() {
        if (!firstMediaReported && isValid && bytesWritten >= MIN_PACKETS * TS_PACKET_BYTES) {
            firstMediaReported = true
            onFirstValidatedMedia()
        }
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    fun ensureValid() {
        if (bytesWritten < MIN_PACKETS * TS_PACKET_BYTES || bytesWritten % TS_PACKET_BYTES != 0L || !isValid) {
            throw IOException("Output is not a complete MPEG-TS packet stream")
        }
    }

    private companion object {
        const val SYNC_BYTE = 0x47
        const val TS_PACKET_BYTES = 188L
        const val MIN_PACKETS = 64L
    }
}
