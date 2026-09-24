package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId

enum class PlaybackPresentation {
    NONE,
    PREVIEW,
    FULLSCREEN,
    PICTURE_IN_PICTURE,
}

enum class PlaybackReadiness {
    IDLE,
    PREPARING,
    PREPARED,
    UNAVAILABLE,
}

enum class PlaybackTrackKind {
    AUDIO,
    SUBTITLE,
}

enum class PlaybackTrackSelectionIssue {
    AUDIO_UNSUPPORTED,
    SUBTITLE_UNSUPPORTED,
    SELECTION_FAILED,
}

data class PlaybackTrackOption(
    val id: String,
    val kind: PlaybackTrackKind,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val role: String? = null,
    val supported: Boolean,
    val selected: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Playback track id must not be blank" }
        require(label.isNotBlank()) { "Playback track label must not be blank" }
    }
}

data class PlaybackTrackSnapshot(
    val audioTracks: List<PlaybackTrackOption> = emptyList(),
    val subtitleTracks: List<PlaybackTrackOption> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val selectedSubtitleTrackId: String? = null,
    val selectionIssue: PlaybackTrackSelectionIssue? = null,
)

data class PlaybackFallbackState(
    val attempted: Boolean = false,
    val active: Boolean = false,
)

sealed interface PlaybackTarget {
    val sourceId: SourceId

    sealed interface Library : PlaybackTarget {
        val offlineDownloadId: String?
    }

    data class LiveChannel(
        override val sourceId: SourceId,
        val channelId: String,
    ) : PlaybackTarget {
        init {
            require(channelId.isNotBlank()) { "Playback channel id must not be blank" }
        }
    }

    data class CatchUp(
        override val sourceId: SourceId,
        val channelId: String,
        val programId: String,
        val title: String,
        val startEpochSeconds: Long,
        val endEpochSeconds: Long,
    ) : PlaybackTarget {
        init {
            require(channelId.isNotBlank()) { "Catch-up channel id must not be blank" }
            require(programId.isNotBlank()) { "Catch-up program id must not be blank" }
            require(title.isNotBlank()) { "Catch-up title must not be blank" }
            require(startEpochSeconds > 0L) { "Catch-up start must be positive" }
            require(endEpochSeconds > startEpochSeconds) { "Catch-up end must follow start" }
        }
    }

    data class Movie(
        override val sourceId: SourceId,
        val movieId: String,
        override val offlineDownloadId: String? = null,
    ) : Library {
        init {
            require(movieId.isNotBlank()) { "Playback movie id must not be blank" }
            require(offlineDownloadId == null || offlineDownloadId.isNotBlank()) {
                "Offline download id must not be blank"
            }
        }
    }

    data class Episode(
        override val sourceId: SourceId,
        val episodeId: String,
        override val offlineDownloadId: String? = null,
        val seriesId: String? = null,
    ) : Library {
        init {
            require(episodeId.isNotBlank()) { "Playback episode id must not be blank" }
            require(offlineDownloadId == null || offlineDownloadId.isNotBlank()) {
                "Offline download id must not be blank"
            }
            require(seriesId == null || seriesId.isNotBlank()) {
                "Playback series id must not be blank"
            }
        }
    }
}

data class PlaybackSessionState(
    val target: PlaybackTarget? = null,
    val presentation: PlaybackPresentation = PlaybackPresentation.NONE,
    val readiness: PlaybackReadiness = PlaybackReadiness.IDLE,
    val playWhenReady: Boolean = false,
    val tracks: PlaybackTrackSnapshot = PlaybackTrackSnapshot(),
    val fallback: PlaybackFallbackState = PlaybackFallbackState(),
    val speed: Float = PlaybackSpeedPolicy.DEFAULT_SPEED,
    val endedNaturally: Boolean = false,
)

object PlaybackSessionPolicy {
    fun activateLiveChannel(
        current: PlaybackSessionState,
        target: PlaybackTarget.LiveChannel,
    ): PlaybackSessionState {
        if (current.target == target) {
            return if (current.presentation == PlaybackPresentation.PREVIEW) {
                current.copy(presentation = PlaybackPresentation.FULLSCREEN)
            } else {
                current
            }
        }

        return PlaybackSessionState(
            target = target,
            presentation = PlaybackPresentation.PREVIEW,
            readiness = PlaybackReadiness.PREPARING,
            playWhenReady = true,
        )
    }

    fun activateCatchUp(
        current: PlaybackSessionState,
        target: PlaybackTarget.CatchUp,
    ): PlaybackSessionState =
        if (current.target == target) {
            current.copy(
                presentation = PlaybackPresentation.PREVIEW,
                endedNaturally = false,
            )
        } else {
            PlaybackSessionState(
                target = target,
                presentation = PlaybackPresentation.PREVIEW,
                readiness = PlaybackReadiness.PREPARING,
                playWhenReady = true,
            )
        }

    fun activateLibraryMedia(
        current: PlaybackSessionState,
        target: PlaybackTarget.Library,
    ): PlaybackSessionState {
        if (current.target == target) {
            return current.copy(presentation = PlaybackPresentation.FULLSCREEN)
        }

        return PlaybackSessionState(
            target = target,
            presentation = PlaybackPresentation.FULLSCREEN,
            readiness = PlaybackReadiness.PREPARING,
            playWhenReady = true,
        )
    }

    fun present(
        current: PlaybackSessionState,
        presentation: PlaybackPresentation,
    ): PlaybackSessionState {
        if (current.target == null) return PlaybackSessionState()
        if (presentation == PlaybackPresentation.NONE) return PlaybackSessionState()
        return current.copy(presentation = presentation)
    }
}

object PlaybackPictureInPictureActionPolicy {
    fun showsPlayPause(state: PlaybackSessionState): Boolean =
        state.readiness == PlaybackReadiness.PREPARED &&
            when (state.target) {
                is PlaybackTarget.Library,
                is PlaybackTarget.CatchUp,
                -> true
                is PlaybackTarget.LiveChannel,
                null,
                -> false
            }
}

object PlaybackPictureInPicturePolicy {
    fun isEligible(state: PlaybackSessionState): Boolean {
        if (state.readiness != PlaybackReadiness.PREPARED) return false
        return when (state.target) {
            is PlaybackTarget.LiveChannel,
            is PlaybackTarget.CatchUp,
            ->
                state.presentation == PlaybackPresentation.PREVIEW ||
                    state.presentation == PlaybackPresentation.FULLSCREEN ||
                    state.presentation == PlaybackPresentation.PICTURE_IN_PICTURE
            is PlaybackTarget.Library ->
                state.presentation == PlaybackPresentation.FULLSCREEN ||
                    state.presentation == PlaybackPresentation.PICTURE_IN_PICTURE
            null -> false
        }
    }
}

object PlaybackTrackLabelPolicy {
    fun label(
        kind: PlaybackTrackKind,
        ordinal: Int,
        labelHint: String?,
        language: String?,
        @Suppress("UNUSED_PARAMETER") codec: String?,
        channelCount: Int?,
        role: String?,
    ): String {
        require(ordinal > 0) { "Track ordinal must be positive" }

        val parts = linkedSetOf<String>()
        language.normalizedPart()
            ?.replaceFirstChar { character -> character.titlecase() }
            ?.let(parts::add)

        when (kind) {
            PlaybackTrackKind.AUDIO -> {
                role.normalizedPart()?.let(parts::add)
                channelCount
                    ?.takeIf { it > 0 }
                    ?.let { count ->
                        parts += when (count) {
                            1 -> "Mono"
                            2 -> "Stereo"
                            6 -> "5.1"
                            8 -> "7.1"
                            else -> "${count}ch"
                        }
                    }
            }

            PlaybackTrackKind.SUBTITLE ->
                role.normalizedPart()?.let(parts::add)
        }

        if (parts.isEmpty()) {
            labelHint.normalizedPart()?.let(parts::add)
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            ?: when (kind) {
                PlaybackTrackKind.AUDIO -> "Audio $ordinal"
                PlaybackTrackKind.SUBTITLE -> "Subtitle $ordinal"
            }
    }

    private fun String?.normalizedPart(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }
}

enum class PlaybackFallbackReason {
    DECODER_OR_FORMAT,
    AUDIO_SELECTION,
    PROLONGED_BUFFERING,
    OTHER,
}

object PlaybackSpeedPolicy {
    const val DEFAULT_SPEED: Float = 1f

    val supportedSpeeds: List<Float> = listOf(
        0.5f,
        0.75f,
        1f,
        1.25f,
        1.5f,
        2f,
    )

    fun isSupported(speed: Float): Boolean = speed in supportedSpeeds
}

object PlaybackFallbackPolicy {
    fun canAttempt(
        hasFallback: Boolean,
        alreadyAttempted: Boolean,
        reason: PlaybackFallbackReason,
    ): Boolean =
        hasFallback &&
            !alreadyAttempted &&
            reason in setOf(
                PlaybackFallbackReason.DECODER_OR_FORMAT,
                PlaybackFallbackReason.AUDIO_SELECTION,
                PlaybackFallbackReason.PROLONGED_BUFFERING,
                PlaybackFallbackReason.OTHER,
            )
}

internal sealed interface LivePlaybackSource {
    class Direct(
        internal val streamLocator: String,
    ) : LivePlaybackSource {
        init {
            require(streamLocator.isNotBlank()) { "Stream locator must not be blank" }
        }

        override fun toString(): String = "Direct(streamLocator=<redacted>)"
    }

    class Xtream(
        internal val baseUrl: String,
        internal val username: String,
        internal val password: String,
        internal val streamId: String,
        internal val opaqueStreamIdentity: String,
    ) : LivePlaybackSource {
        init {
            require(baseUrl.isNotBlank()) { "Xtream base URL must not be blank" }
            require(username.isNotBlank()) { "Xtream username must not be blank" }
            require(password.isNotBlank()) { "Xtream password must not be blank" }
            require(streamId.isNotBlank()) { "Xtream stream id must not be blank" }
        }

        override fun toString(): String =
            "Xtream(baseUrl=<redacted>, username=<redacted>, password=<redacted>, streamId=<redacted>, opaqueStreamIdentity=<redacted>)"
    }
}

internal data class PreparedPlaybackAlternative(
    internal val uri: String,
    internal val mimeType: String? = null,
) {
    init {
        require(uri.isNotBlank()) { "Prepared fallback playback URI must not be blank" }
    }

    override fun toString(): String =
        "PreparedPlaybackAlternative(uri=<redacted>, mimeType=${mimeType ?: "<unspecified>"})"
}

internal data class PreparedPlaybackMedia(
    internal val uri: String,
    internal val mimeType: String? = null,
    internal val fallback: PreparedPlaybackAlternative? = null,
) {
    init {
        require(uri.isNotBlank()) { "Prepared playback URI must not be blank" }
    }

    internal fun primaryOnly(): PreparedPlaybackMedia =
        if (fallback == null) this else copy(fallback = null)

    override fun toString(): String =
        "PreparedPlaybackMedia(uri=<redacted>, mimeType=${mimeType ?: "<unspecified>"}, fallback=${if (fallback == null) "<none>" else "<available>"})"
}

internal interface LivePlaybackSourceResolver {
    suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource?
}

internal interface LivePlaybackMediaPreparer {
    fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia?
}

internal interface LibraryPlaybackMediaResolver {
    suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia?
}

internal interface CatchUpPlaybackMediaResolver {
    suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia?
}

internal enum class PlaybackEngineReadiness {
    PREPARING,
    READY,
    ENDED,
    FAILED,
}

internal enum class PlaybackEngineTrackKind {
    AUDIO,
    SUBTITLE,
}

internal data class PlaybackEngineTrack(
    val id: String,
    val kind: PlaybackEngineTrackKind,
    val labelHint: String?,
    val language: String?,
    val codec: String?,
    val channelCount: Int?,
    val role: String?,
    val supported: Boolean,
    val selected: Boolean,
)

internal data class PlaybackEngineTracks(
    val tracks: List<PlaybackEngineTrack>,
)

internal enum class PlaybackEngineSelectionResult {
    APPLIED,
    UNSUPPORTED,
    FAILED,
}

internal enum class PlaybackEngineFailureClass {
    DECODER_OR_FORMAT,
    AUDIO,
    OTHER,
}

internal data class PlaybackEngineEvent(
    val mediaRevision: Long,
    val readiness: PlaybackEngineReadiness? = null,
    val tracks: PlaybackEngineTracks? = null,
    val failureClass: PlaybackEngineFailureClass? = null,
) {
    init {
        require(readiness != null || tracks != null) {
            "Playback engine event must contain readiness or tracks"
        }
    }
}

internal interface PlaybackEngine {
    fun setEventListener(listener: (PlaybackEngineEvent) -> Unit)

    fun replace(media: PreparedPlaybackMedia): Long

    fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult

    fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult

    fun play(): Boolean = false

    fun pause(): Boolean = false

    fun setVolume(volume: Float): Boolean = false

    fun setSpeed(speed: Float): Boolean = false

    fun clear()

    fun release()
}
