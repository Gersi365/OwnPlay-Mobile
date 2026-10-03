package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.AndroidDownloadStorage
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingFinalizationState
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingTransitionPolicy
import app.ownplay.mobile.feature.playback.data.DefaultLivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.data.SourceBackedLivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.sources.domain.SourceId
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Request

internal class LiveRecordingCaptureExecutor(
    private val repository: LiveRecordingRepository,
    private val sourceResolver: SourceBackedLivePlaybackSourceResolver,
    private val downloadStorage: AndroidDownloadStorage,
    private val downloadPreferencesRepository: DownloadPreferencesRepository,
) {
    private val mediaPreparer = DefaultLivePlaybackMediaPreparer()
    private val captureClient = LiveRecordingCaptureClient()

    suspend fun recoverInterruptedRecordings(interrupted: List<LiveRecording>) = withContext(Dispatchers.IO) {
        for (recording in interrupted) {
            val current = repository.get(recording.recordingId) ?: continue
            if (current != recording) continue
            var pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput? = null
            var recovered = false
            try {
                pending = recording.pendingOutputDescriptor?.let { descriptor ->
                    downloadStorage.recoverOwnedPending(DownloadId("recording-" + recording.recordingId), descriptor)
                }
                if (pending != null) {
                    val validator = TransportStreamValidatingOutputStream(object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                    })
                    val input = downloadStorage.openPendingInput(pending)
                        ?: throw IOException("Pending output is unavailable")
                    input.use { it.copyTo(validator) }
                    validator.ensureValid()
                    recovered = publishPartialIfSafe(
                        recording.copy(failureReason = "PROCESS_INTERRUPTED"), pending, validator,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Source URLs and platform exception messages are never persisted here.
            }
            if (!recovered) {
                // An unverified descriptor is never deleted or published.
                pending?.let { runCatching { downloadStorage.discard(it) } }
                repository.update(recording.recordingId) { latest ->
                    latest.copy(
                        status = LiveRecordingStatus.FAILED,
                        safeError = "Recording was interrupted; no verified partial file was recovered.",
                        failureReason = "PROCESS_INTERRUPTED",
                        finalizationState = LiveRecordingFinalizationState.DISCARDED,
                        pendingOutputDescriptor = null,
                    )
                }
            }
        }
    }

    suspend fun execute(recordingId: String): Boolean {
        val scheduled = repository.get(recordingId) ?: return false
        if (scheduled.status != LiveRecordingStatus.SCHEDULED) return false
        val now = System.currentTimeMillis() / 1_000L
        if (scheduled.endEpochSeconds <= now) {
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "The scheduled recording window has passed.",
                ),
            )
            return false
        }
        val captureRequested = System.currentTimeMillis() / 1_000L
        val activeRecording = scheduled.copy(
                status = LiveRecordingStatus.STARTING,
                captureRequestedEpochSeconds = captureRequested,
                actualStartEpochSeconds = null,
                deadlineEpochSeconds = scheduled.endEpochSeconds,
                progressBytes = 0L,
                finalizationState = LiveRecordingFinalizationState.NOT_STARTED,
                safeError = null,
                failureReason = null,
            )
        if (!repository.put(activeRecording)) return false

        var pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput? = null
        var validatingOutput: TransportStreamValidatingOutputStream? = null
        return try {
            val source = sourceResolver.resolve(
                PlaybackTarget.LiveChannel(
                    sourceId = SourceId(scheduled.sourceId),
                    channelId = scheduled.channelId,
                ),
            ) ?: throw IOException("Source is unavailable")
            val prepared = mediaPreparer.prepare(source) ?: throw IOException("Source is unavailable")
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
            ) ?: throw IOException("Storage is unavailable")
            if (!repository.update(recordingId) { it.copy(pendingOutputDescriptor = downloadStorage.describePending(pending!!)) }) {
                throw IOException("Pending recording metadata could not be saved")
            }
            validatingOutput = TransportStreamValidatingOutputStream(pending!!.outputStream) {
                if (!repository.update(recordingId) { current ->
                        LiveRecordingTransitionPolicy.firstValidatedMedia(current, System.currentTimeMillis() / 1_000L)
                    }
                ) throw IOException("Recording start metadata could not be saved")
            }

            withContext(Dispatchers.IO) {
                captureClient.capture(
                    uri = selected.first,
                    isHls = selected.second,
                    startEpochMillis = captureRequested * 1_000L,
                    endEpochMillis = scheduled.deadlineEpochSeconds * 1_000L,
                    output = validatingOutput!!,
                    onProgress = { bytes ->
                        val current = repository.get(recordingId) ?: return@capture
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
                ?: throw IOException("Recording output is unavailable")
            if (!isPlausibleTransportStream(size) || validatingOutput?.bytesWritten != size) {
                throw IOException("Recording output is incomplete")
            }
            val reference = downloadStorage.publish(pending!!)
                ?: throw IOException("Recording output could not be finalized")
            if (!repository.put(
                    (repository.get(recordingId) ?: activeRecording).copy(
                    status = LiveRecordingStatus.COMPLETED,
                    localReference = reference,
                    partialLocalReference = null,
                    pendingOutputDescriptor = null,
                    finalizationState = LiveRecordingFinalizationState.PUBLISHED,
                    progressBytes = size,
                    safeError = null,
                    failureReason = null,
                ),
            )) {
                downloadStorage.removePublished(reference)
                throw IOException("Recording metadata could not be saved")
            }
            pending = null
            true
        } catch (cancelled: CancellationException) {
            val current = repository.get(recordingId) ?: scheduled
            pending?.let { runCatching { it.outputStream.close() } }
            if (!publishPartialIfSafe(current, pending, validatingOutput)) {
                pending?.let { runCatching { downloadStorage.discard(it) } }
                if (current.failureReason != "ANDROID_FGS_TIME_LIMIT") {
                    repository.put(
                        current.copy(
                            status = LiveRecordingStatus.FAILED,
                            finalizationState = LiveRecordingFinalizationState.DISCARDED,
                            pendingOutputDescriptor = null,
                            failureReason = "STOPPED_BEFORE_FINALIZATION",
                            safeError = "Recording stopped before a safe partial file could be saved.",
                        ),
                    )
                }
            }
            throw cancelled
        } catch (error: Exception) {
            val current = repository.get(recordingId) ?: scheduled
            pending?.let { runCatching { it.outputStream.close() } }
            if (!publishPartialIfSafe(current, pending, validatingOutput)) {
                pending?.let { runCatching { downloadStorage.discard(it) } }
                repository.put(
                    current.copy(
                        status = LiveRecordingStatus.FAILED,
                        finalizationState = LiveRecordingFinalizationState.DISCARDED,
                        pendingOutputDescriptor = null,
                        failureReason = error.javaClass.simpleName.take(48),
                        safeError = "The recording could not be completed.",
                    ),
                )
            }
            false
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
        val reference = downloadStorage.publish(pending) ?: return false
        val saved = repository.put(
            current.copy(
                status = LiveRecordingStatus.PARTIAL,
                localReference = reference,
                partialLocalReference = reference,
                pendingOutputDescriptor = null,
                progressBytes = size,
                finalizationState = LiveRecordingFinalizationState.PARTIAL_PUBLISHED,
                failureReason = if (current.failureReason == "PROCESS_INTERRUPTED") "PROCESS_INTERRUPTED"
                    else if (current.status == LiveRecordingStatus.FINALIZING) "STOPPED_BY_USER" else current.failureReason,
                safeError = if (current.failureReason == "PROCESS_INTERRUPTED") {
                    "Recovered a verified partial recording after interruption."
                } else if (current.status == LiveRecordingStatus.FINALIZING) {
                    "Saved as a partial recording."
                } else {
                    "Saved a verified partial recording after the stream ended early."
                },
            ),
        )
        if (!saved) {
            downloadStorage.removePublished(reference)
            return false
        }
        return true
    }

    private fun isPlausibleTransportStream(size: Long): Boolean =
        size >= MIN_PARTIAL_TS_BYTES && size % TS_PACKET_BYTES == 0L

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
        throw IOException("Live source is not an MPEG-TS transport stream")
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

internal class LiveRecordingCaptureClient {
    private val playlistClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val streamClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun capture(
        uri: String,
        isHls: Boolean,
        startEpochMillis: Long,
        endEpochMillis: Long,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit = {},
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
            val bytes = AtomicLong(0L)
            val reader = async(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Stream unavailable")
                    val body = response.body ?: throw IOException("Stream unavailable")
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
                if (System.currentTimeMillis() < endEpochMillis || bytes.get() == 0L) throw failure
            } finally {
                stopAtEnd.cancel()
                call.cancel()
            }
            if (bytes.get() == 0L) throw IOException("Stream was empty")
            if (System.currentTimeMillis() < endEpochMillis) {
                throw IOException("Live stream ended before the selected program")
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
            val text = fetchText(playlistUri)
            val playlist = HlsTransportStreamPlaylistParser.parse(playlistUri, text)
            if (playlist.variants.isNotEmpty()) {
                playlistUri = playlist.variants.maxByOrNull { it.bandwidth }?.uri
                    ?: throw IOException("Playlist is unavailable")
                initial = true
                continue
            }
            val hasProgramTime = playlist.segments.any { it.programDateTimeEpochMillis != null }
            if (
                !playlist.endList && !hasProgramTime &&
                playlist.segments.any { it.mediaSequence == null }
            ) {
                throw IOException("Live HLS playlist has no stable segment sequence")
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
                throw IOException("Playlist has no time information for a program window")
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
                    throw IOException("Only MPEG-TS HLS segments are supported")
                }
                totalBytes += fetchSegment(segment.uri, output, totalBytes, onProgress)
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
        if (totalBytes <= 0L) throw IOException("Playlist contained no supported media")
    }

    private suspend fun fetchText(uri: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(uri).build()
        val call = playlistClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Playlist unavailable")
                response.body?.string() ?: throw IOException("Playlist unavailable")
            }
        } finally {
            cancellation?.dispose()
        }
    }

    private suspend fun fetchSegment(
        uri: String,
        output: OutputStream,
        currentTotal: Long,
        onProgress: suspend (Long) -> Unit,
    ): Long =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(uri).build()
            val call = playlistClient.newCall(request)
            val cancellation = currentCoroutineContext()[Job]
                ?.invokeOnCompletion { cause -> if (cause is CancellationException) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Segment unavailable")
                    val body = response.body ?: throw IOException("Segment unavailable")
                    var bytes = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            onProgress(currentTotal + bytes)
                        }
                    }
                    bytes
                }
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
            throw IOException("Invalid HLS playlist")
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
                    throw IOException("Encrypted HLS recordings are not supported")
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) ||
                    line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) ->
                    throw IOException("Fragmented HLS recordings are not supported")
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
                        .getOrElse { throw IOException("Invalid HLS segment URI") }
                    if (pendingBandwidth != null) {
                        variants += HlsTransportStreamVariant(pendingBandwidth!!, resolved)
                        pendingBandwidth = null
                    } else {
                        val duration = pendingDuration ?: throw IOException("Missing HLS segment duration")
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
