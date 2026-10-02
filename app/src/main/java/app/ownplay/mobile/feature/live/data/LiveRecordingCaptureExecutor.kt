package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.downloads.data.AndroidDownloadStorage
import app.ownplay.mobile.downloads.data.ResolvedDownloadMedia
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingRepository
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
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
        repository.put(scheduled.copy(status = LiveRecordingStatus.RECORDING, safeError = null))

        var pending: app.ownplay.mobile.downloads.data.PendingDownloadOutput? = null
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
                displayName = recordingFileName(scheduled.title, scheduled.startEpochSeconds),
                relativeDirectories = listOf("Recordings"),
            )
            pending = downloadStorage.openPendingAtDestination(
                downloadId = id,
                media = output,
                destinationRelativePath = preferences.destinationRelativePath,
            ) ?: throw IOException("Storage is unavailable")

            withContext(Dispatchers.IO) {
                captureClient.capture(
                    uri = selected.first,
                    isHls = selected.second,
                    startEpochMillis = scheduled.startEpochSeconds * 1_000L,
                    endEpochMillis = scheduled.endEpochSeconds * 1_000L,
                    output = pending!!.outputStream,
                )
            }
            pending!!.outputStream.flush()
            val size = downloadStorage.verifiedSize(pending!!)
                ?: throw IOException("Recording output is unavailable")
            if (size <= 0L) throw IOException("Recording output is empty")
            val reference = downloadStorage.publish(pending!!)
                ?: throw IOException("Recording output could not be finalized")
            pending = null
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.COMPLETED,
                    localReference = reference,
                    safeError = null,
                ),
            )
        } catch (cancelled: CancellationException) {
            pending?.let { runCatching { downloadStorage.discard(it) } }
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "Recording stopped before completion.",
                ),
            )
            throw cancelled
        } catch (_: Exception) {
            pending?.let { runCatching { downloadStorage.discard(it) } }
            repository.put(
                scheduled.copy(
                    status = LiveRecordingStatus.FAILED,
                    safeError = "The recording could not be completed.",
                ),
            )
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
        throw IOException("Live source is not an MPEG-TS transport stream")
    }

    private fun isDirectTransportStream(uri: String, mimeType: String?): Boolean {
        if (mimeType?.contains("mp2t", ignoreCase = true) == true) return true
        val path = uri.substringBefore('?').substringAfterLast('/')
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
        return extension.isBlank() || extension.equals("ts", ignoreCase = true)
    }

    private fun recordingFileName(title: String, startEpochSeconds: Long): String {
        val safeTitle = title
            .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            .trim()
            .take(72)
            .ifBlank { "Live recording" }
        return safeTitle + "-" + startEpochSeconds + ".ts"
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
    ) {
        if (isHls) {
            captureHls(uri, startEpochMillis, endEpochMillis, output)
        } else {
            captureDirect(uri, endEpochMillis, output)
        }
    }

    private suspend fun captureDirect(uri: String, endEpochMillis: Long, output: OutputStream) =
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
                            bytes.addAndGet(count.toLong())
                        }
                    }
                }
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
            val selectedSegments = when {
                initial && hasProgramTime -> playlist.segments.filter { segment ->
                    val start = segment.programDateTimeEpochMillis ?: return@filter false
                    start + (segment.durationSeconds * 1_000.0).toLong() > startEpochMillis &&
                        start < endEpochMillis
                }
                initial && playlist.endList -> playlist.segments
                initial -> emptyList()
                else -> playlist.segments.filterNot { it.uri in seen }.filter { segment ->
                    val start = segment.programDateTimeEpochMillis
                    start == null ||
                        (start < endEpochMillis &&
                            start + (segment.durationSeconds * 1_000.0).toLong() > startEpochMillis)
                }
            }
            if (initial && playlist.endList && !hasProgramTime) {
                throw IOException("Playlist has no time information for a program window")
            }
            for (segment in selectedSegments) {
                if (!seen.add(segment.uri)) continue
                if (!segment.uri.substringBefore('?').endsWith(".ts", ignoreCase = true)) {
                    throw IOException("Only MPEG-TS HLS segments are supported")
                }
                totalBytes += fetchSegment(segment.uri, output)
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
        playlistClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Playlist unavailable")
            response.body?.string() ?: throw IOException("Playlist unavailable")
        }
    }

    private suspend fun fetchSegment(uri: String, output: OutputStream): Long =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(uri).build()
            playlistClient.newCall(request).execute().use { response ->
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
                    }
                }
                bytes
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
)

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
                        segments += HlsTransportStreamSegment(resolved, duration, nextProgramTime)
                        nextProgramTime = nextProgramTime?.plus((duration * 1_000.0).toLong())
                        pendingDuration = null
                    }
                }
            }
        }
        return ParsedHlsTransportStreamPlaylist(variants, segments, targetDuration, endList)
    }
}
