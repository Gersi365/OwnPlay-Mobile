package app.ownplay.player.playback

import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackTrackKind {
    AUDIO,
    SUBTITLE,
}

data class PlaybackTrackOption(
    val id: String,
    val kind: PlaybackTrackKind,
    val label: String,
    val language: String?,
    val selectedByPlayer: Boolean,
    val supported: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Track option ID must not be blank" }
        require(label.isNotBlank()) { "Track option label must not be blank" }
        require(language == null || language.isNotBlank()) {
            "Track language must be null or non-blank"
        }
    }

    override fun toString(): String =
        "PlaybackTrackOption(id=<opaque>, kind=$kind, label=$label, language=$language, " +
            "selectedByPlayer=$selectedByPlayer, supported=$supported)"
}

data class PlaybackDiagnostics(
    val rendererPolicy: String = "Platform first · FFmpeg fallback",
    val videoMimeType: String? = null,
    val videoCodecs: String? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoDecoder: String? = null,
    val audioMimeType: String? = null,
    val audioCodecs: String? = null,
    val audioSampleRate: Int? = null,
    val audioChannelCount: Int? = null,
    val audioLanguage: String? = null,
    val audioDecoder: String? = null,
) {
    val usingFfmpegAudio: Boolean
        get() = audioDecoder?.contains("ffmpeg", ignoreCase = true) == true
}

sealed interface PlaybackAudioSelection {
    data object Default : PlaybackAudioSelection

    data class Specific(
        val trackId: String,
    ) : PlaybackAudioSelection {
        init {
            require(trackId.isNotBlank()) { "Audio track ID must not be blank" }
        }

        override fun toString(): String = "Specific(trackId=<opaque>)"
    }
}

sealed interface PlaybackSubtitleSelection {
    data object Default : PlaybackSubtitleSelection
    data object Off : PlaybackSubtitleSelection

    data class Specific(
        val trackId: String,
    ) : PlaybackSubtitleSelection {
        init {
            require(trackId.isNotBlank()) { "Subtitle track ID must not be blank" }
        }

        override fun toString(): String = "Specific(trackId=<opaque>)"
    }
}

data class PlaybackTrackState(
    val audioTracks: List<PlaybackTrackOption> = emptyList(),
    val subtitleTracks: List<PlaybackTrackOption> = emptyList(),
    val audioSelection: PlaybackAudioSelection = PlaybackAudioSelection.Default,
    val subtitleSelection: PlaybackSubtitleSelection = PlaybackSubtitleSelection.Default,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
) {
    init {
        require(audioTracks.all { it.kind == PlaybackTrackKind.AUDIO }) {
            "Audio track state may contain only audio options"
        }
        require(subtitleTracks.all { it.kind == PlaybackTrackKind.SUBTITLE }) {
            "Subtitle track state may contain only subtitle options"
        }
        require((audioTracks + subtitleTracks).map { it.id }.distinct().size == audioTracks.size + subtitleTracks.size) {
            "Track option IDs must be unique within the current stream"
        }
    }
}

interface PlaybackTrackController {
    val state: StateFlow<PlaybackTrackState>

    fun selectAudio(selection: PlaybackAudioSelection)
    fun selectSubtitle(selection: PlaybackSubtitleSelection)
}

internal object PlaybackTrackSelectionPolicy {
    fun withTracks(
        state: PlaybackTrackState,
        audioTracks: List<PlaybackTrackOption>,
        subtitleTracks: List<PlaybackTrackOption>,
    ): PlaybackTrackState = state.copy(
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        audioSelection = normalizeAudioSelection(state.audioSelection, audioTracks),
        subtitleSelection = normalizeSubtitleSelection(state.subtitleSelection, subtitleTracks),
    )

    fun selectAudio(
        state: PlaybackTrackState,
        selection: PlaybackAudioSelection,
    ): PlaybackTrackState = when (selection) {
        PlaybackAudioSelection.Default -> state.copy(audioSelection = selection)
        is PlaybackAudioSelection.Specific -> if (
            state.audioTracks.any { option ->
                option.id == selection.trackId && option.supported
            }
        ) {
            state.copy(audioSelection = selection)
        } else {
            state
        }
    }

    fun selectSubtitle(
        state: PlaybackTrackState,
        selection: PlaybackSubtitleSelection,
    ): PlaybackTrackState = when (selection) {
        PlaybackSubtitleSelection.Default,
        PlaybackSubtitleSelection.Off,
        -> state.copy(subtitleSelection = selection)

        is PlaybackSubtitleSelection.Specific -> if (
            state.subtitleTracks.any { option -> option.id == selection.trackId && option.supported }
        ) {
            state.copy(subtitleSelection = selection)
        } else {
            state
        }
    }

    fun resetForNewMedia(): PlaybackTrackState = PlaybackTrackState()

    fun resetAfterPlayerFailure(state: PlaybackTrackState): PlaybackTrackState =
        PlaybackTrackState(diagnostics = state.diagnostics)

    private fun normalizeAudioSelection(
        selection: PlaybackAudioSelection,
        options: List<PlaybackTrackOption>,
    ): PlaybackAudioSelection = when (selection) {
        PlaybackAudioSelection.Default -> selection
        is PlaybackAudioSelection.Specific -> if (
            options.any { option -> option.id == selection.trackId && option.supported }
        ) {
            selection
        } else {
            PlaybackAudioSelection.Default
        }
    }

    private fun normalizeSubtitleSelection(
        selection: PlaybackSubtitleSelection,
        options: List<PlaybackTrackOption>,
    ): PlaybackSubtitleSelection = when (selection) {
        PlaybackSubtitleSelection.Default,
        PlaybackSubtitleSelection.Off,
        -> selection

        is PlaybackSubtitleSelection.Specific -> if (
            options.any { option -> option.id == selection.trackId && option.supported }
        ) {
            selection
        } else {
            PlaybackSubtitleSelection.Default
        }
    }
}

internal object PlaybackTrackLabelFormatter {
    private const val MAX_LABEL_LENGTH = 80

    fun format(
        kind: PlaybackTrackKind,
        rawLabel: String?,
        rawLanguage: String?,
        ordinal: Int,
    ): String {
        require(ordinal > 0) { "Track ordinal must be positive" }

        val label = normalizeMetadata(rawLabel)
        val language = normalizeMetadata(rawLanguage)

        return when {
            label != null && language != null && !label.equals(language, ignoreCase = true) ->
                "$label · $language"

            label != null -> label
            language != null -> language
            else -> when (kind) {
                PlaybackTrackKind.AUDIO -> "Audio $ordinal"
                PlaybackTrackKind.SUBTITLE -> "Subtitle $ordinal"
            }
        }
    }

    fun language(rawLanguage: String?): String? = normalizeMetadata(rawLanguage)

    private fun normalizeMetadata(raw: String?): String? {
        val normalized = raw
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_LABEL_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: return null

        val lower = normalized.lowercase(Locale.ROOT)
        val sensitiveMarker = listOf(
            "://",
            "password=",
            "passwd=",
            "token=",
            "username=",
            "authorization=",
            "bearer ",
        ).any(lower::contains)

        return normalized.takeUnless { sensitiveMarker }
    }
}
