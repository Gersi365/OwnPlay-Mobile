package app.ownplay.mobile.feature.live.data

import android.util.Log
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineFailureClass
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineRecoveryKind
import app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage
import okhttp3.HttpUrl
import okhttp3.MediaType

internal enum class LiveDvrDiagnosticEvent {
    DIRECT_RESPONSE,
    HLS_PLAYLIST_RESPONSE,
    HLS_SEGMENT_RESPONSE,
    HLS_SEGMENT_BODY,
    INGRESS_ATTEMPT,
    VALIDATED_MEDIA,
    INGRESS_FAILURE,
    STORAGE_HEADROOM_ACCEPTED,
    STORAGE_HEADROOM_DENIED,
    CAPACITY_ADMISSION_DENIED,
    MEDIA3_ERROR,
}

internal enum class LiveDvrMediaType {
    HLS_PLAYLIST,
    MPEG_TS,
    OTHER,
    UNKNOWN,
}

internal enum class LiveDvrRedirectClass {
    NOT_REDIRECTED,
    SAME_ORIGIN,
    DIFFERENT_ORIGIN,
    CROSS_SCHEME,
}

internal enum class LiveDvrRepresentation {
    HLS,
    DIRECT_TS,
}

/**
 * Emits only bounded enums and numeric transport facts. Provider URLs, hosts, headers, bodies,
 * source/channel IDs, exception text and credentials are deliberately not accepted here.
 */
internal object LiveDvrDiagnostics {
    private const val TAG = "OwnPlayLiveDvr"

    fun mediaType(value: MediaType?): LiveDvrMediaType = when {
        value == null -> LiveDvrMediaType.UNKNOWN
        value.type == "video" && value.subtype == "mp2t" -> LiveDvrMediaType.MPEG_TS
        value.type == "application" && value.subtype.contains("mpegurl", ignoreCase = true) ->
            LiveDvrMediaType.HLS_PLAYLIST
        else -> LiveDvrMediaType.OTHER
    }

    fun redirectClass(original: HttpUrl, effective: HttpUrl): LiveDvrRedirectClass = when {
        original.scheme != effective.scheme -> LiveDvrRedirectClass.CROSS_SCHEME
        original == effective -> LiveDvrRedirectClass.NOT_REDIRECTED
        original.host == effective.host && original.port == effective.port -> LiveDvrRedirectClass.SAME_ORIGIN
        else -> LiveDvrRedirectClass.DIFFERENT_ORIGIN
    }

    fun record(
        event: LiveDvrDiagnosticEvent,
        stage: LiveRecordingCaptureStage? = null,
        category: LiveRecordingFailureCategory? = null,
        statusCode: Int? = null,
        mediaType: LiveDvrMediaType? = null,
        redirectClass: LiveDvrRedirectClass? = null,
        representation: LiveDvrRepresentation? = null,
        candidateIndex: Int? = null,
        retryCount: Int? = null,
        validatedBytes: Long? = null,
        incomingBytes: Long? = null,
        durationMs: Long? = null,
        usableBytes: Long? = null,
        totalBytes: Long? = null,
        headroomBytes: Long? = null,
        recordingCopies: Int? = null,
        playbackStage: PlaybackFailureStage? = null,
        playbackErrorCode: Int? = null,
        playbackFailureClass: PlaybackEngineFailureClass? = null,
        playbackRecoveryKind: PlaybackEngineRecoveryKind? = null,
    ) {
        val fields = buildList {
            stage?.let { add("stage=${it.name}") }
            category?.let { add("category=${it.name}") }
            statusCode?.let { add("status=$it") }
            mediaType?.let { add("mime=${it.name}") }
            redirectClass?.let { add("redirect=${it.name}") }
            representation?.let { add("representation=${it.name}") }
            candidateIndex?.let { add("candidate_index=$it") }
            retryCount?.let { add("retry_count=$it") }
            validatedBytes?.let { add("validated_bytes=$it") }
            incomingBytes?.let { add("incoming_bytes=$it") }
            durationMs?.let { add("duration_ms=$it") }
            usableBytes?.let { add("usable_bytes=$it") }
            totalBytes?.let { add("total_bytes=$it") }
            headroomBytes?.let { add("required_headroom_bytes=$it") }
            recordingCopies?.let { add("recording_copies=$it") }
            playbackStage?.let { add("playback_stage=${it.name}") }
            playbackErrorCode?.let { add("media3_error_code=$it") }
            playbackFailureClass?.let { add("media3_failure_class=${it.name}") }
            playbackRecoveryKind?.let { add("media3_recovery=${it.name}") }
        }.joinToString(" ")
        runCatching { Log.i(TAG, "event=${event.name} $fields") }
    }
}
