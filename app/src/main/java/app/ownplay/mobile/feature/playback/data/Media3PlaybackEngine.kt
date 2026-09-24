package app.ownplay.mobile.feature.playback.data

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.ownplay.mobile.feature.playback.domain.PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineEvent
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineFailureClass
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineSelectionResult
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineTrack
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineTrackKind
import app.ownplay.mobile.feature.playback.domain.PlaybackEngineTracks
import app.ownplay.mobile.feature.playback.domain.PlaybackPositionSnapshot
import app.ownplay.mobile.feature.playback.domain.PlaybackProgressEngine
import app.ownplay.mobile.feature.playback.domain.PreparedPlaybackMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Media3PlaybackEngine internal constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val renderersFactory = DefaultRenderersFactory(applicationContext)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    private val player = ExoPlayer.Builder(applicationContext, renderersFactory)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(applicationContext)
                .setDataSourceFactory(
                    DefaultDataSource.Factory(
                        applicationContext,
                        DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true),
                    ),
                ),
        )
        .build()
    private var attachedSurfaceView: SurfaceView? = null
    private var hasMedia: Boolean = false
    private var userPlayWhenReady: Boolean = false
    private var nextMediaRevision: Long = 0L
    private var activeMediaRevision: Long? = null
    private var eventListener: ((PlaybackEngineEvent) -> Unit)? = null
    private var released: Boolean = false
    private var trackHandles: Map<String, TrackHandle> = emptyMap()
    private val mutableVideoAspectRatio = MutableStateFlow<Float?>(null)
    val videoAspectRatio: StateFlow<Float?> = mutableVideoAspectRatio.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> emitReadiness(PlaybackEngineReadiness.PREPARING)
                        Player.STATE_READY -> emitReadiness(PlaybackEngineReadiness.READY)
                        Player.STATE_ENDED -> emitReadiness(PlaybackEngineReadiness.ENDED)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val width = videoSize.width
                    val height = videoSize.height
                    mutableVideoAspectRatio.value =
                        if (width > 0 && height > 0) {
                            (width.toFloat() * videoSize.pixelWidthHeightRatio) / height.toFloat()
                        } else {
                            null
                        }
                }

                override fun onPlayerError(error: PlaybackException) {
                    emitReadiness(
                        readiness = PlaybackEngineReadiness.FAILED,
                        failureClass = classifyFailure(error),
                    )
                }

                override fun onTracksChanged(tracks: Tracks) {
                    emitTracks(tracks)
                }
            },
        )
    }

    internal fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
        eventListener = listener
    }

    internal fun replace(media: PreparedPlaybackMedia): Long {
        check(!released) { "Playback engine has been released" }

        nextMediaRevision += 1L
        val revision = nextMediaRevision
        activeMediaRevision = null
        hasMedia = true
        userPlayWhenReady = true
        trackHandles = emptyMap()
        resetTrackSelection()

        val mediaItem = MediaItem.Builder()
            .setUri(media.uri)
            .apply {
                media.mimeType?.let(::setMimeType)
            }
            .build()

        player.setMediaItem(mediaItem)
        activeMediaRevision = revision
        emitReadiness(PlaybackEngineReadiness.PREPARING)
        player.prepare()
        player.playWhenReady = attachedSurfaceView != null && userPlayWhenReady
        return revision
    }

    internal fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
        selectTrack(
            kind = PlaybackEngineTrackKind.AUDIO,
            trackId = trackId,
        )

    internal fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
        selectTrack(
            kind = PlaybackEngineTrackKind.SUBTITLE,
            trackId = trackId,
        )

    internal fun positionSnapshot(): PlaybackPositionSnapshot? {
        if (released || !hasMedia || activeMediaRevision == null) return null
        val durationMs = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
        return PlaybackPositionSnapshot(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = durationMs,
            ended = player.playbackState == Player.STATE_ENDED,
        )
    }

    internal fun seekTo(positionMs: Long): Boolean {
        if (released || !hasMedia || activeMediaRevision == null || positionMs < 0L) return false
        return try {
            player.seekTo(positionMs)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun play(): Boolean {
        if (released || !hasMedia || activeMediaRevision == null) return false
        userPlayWhenReady = true
        player.playWhenReady = attachedSurfaceView != null
        return true
    }

    internal fun pause(): Boolean {
        if (released || !hasMedia || activeMediaRevision == null) return false
        userPlayWhenReady = false
        player.playWhenReady = false
        return true
    }

    internal fun setVolume(volume: Float): Boolean {
        if (released || volume !in 0f..1f) return false
        player.volume = volume
        return true
    }

    internal fun setSpeed(speed: Float): Boolean {
        if (released || speed <= 0f) return false
        return try {
            player.setPlaybackSpeed(speed)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun clear() {
        if (released) return
        activeMediaRevision = null
        trackHandles = emptyMap()
        mutableVideoAspectRatio.value = null
        hasMedia = false
        userPlayWhenReady = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    internal fun attachVideoSurface(surfaceView: SurfaceView) {
        check(!released) { "Playback engine has been released" }
        if (attachedSurfaceView === surfaceView) return
        attachedSurfaceView?.let(player::clearVideoSurfaceView)
        player.setVideoSurfaceView(surfaceView)
        attachedSurfaceView = surfaceView
        if (hasMedia) {
            player.playWhenReady = userPlayWhenReady
        }
    }

    internal fun detachVideoSurface(surfaceView: SurfaceView) {
        if (released || attachedSurfaceView !== surfaceView) return
        player.clearVideoSurfaceView(surfaceView)
        attachedSurfaceView = null
        player.playWhenReady = false
    }

    internal fun release() {
        if (released) return
        released = true
        activeMediaRevision = null
        eventListener = null
        trackHandles = emptyMap()
        mutableVideoAspectRatio.value = null
        attachedSurfaceView?.let(player::clearVideoSurfaceView)
        attachedSurfaceView = null
        hasMedia = false
        userPlayWhenReady = false
        player.release()
    }

    private fun selectTrack(
        kind: PlaybackEngineTrackKind,
        trackId: String?,
    ): PlaybackEngineSelectionResult {
        if (released || activeMediaRevision == null) {
            return PlaybackEngineSelectionResult.FAILED
        }

        val trackType = kind.media3TrackType
        return try {
            val builder = player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(trackType)

            if (trackId == null) {
                builder.setTrackTypeDisabled(
                    trackType,
                    kind == PlaybackEngineTrackKind.SUBTITLE,
                )
            } else {
                val handle = trackHandles[trackId]
                if (handle == null || handle.kind != kind || !handle.supported) {
                    return PlaybackEngineSelectionResult.UNSUPPORTED
                }
                builder
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(
                        TrackSelectionOverride(
                            handle.mediaTrackGroup,
                            handle.trackIndex,
                        ),
                    )
            }

            player.trackSelectionParameters = builder.build()
            PlaybackEngineSelectionResult.APPLIED
        } catch (_: Exception) {
            PlaybackEngineSelectionResult.FAILED
        }
    }

    private fun resetTrackSelection() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun emitReadiness(
        readiness: PlaybackEngineReadiness,
        failureClass: PlaybackEngineFailureClass? = null,
    ) {
        val revision = activeMediaRevision ?: return
        eventListener?.invoke(
            PlaybackEngineEvent(
                mediaRevision = revision,
                readiness = readiness,
                failureClass = failureClass,
            ),
        )
    }

    private fun emitTracks(tracks: Tracks) {
        val revision = activeMediaRevision ?: return
        val nextHandles = linkedMapOf<String, TrackHandle>()
        val descriptors = mutableListOf<PlaybackEngineTrack>()

        tracks.groups.forEachIndexed { groupIndex, group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> PlaybackEngineTrackKind.AUDIO
                C.TRACK_TYPE_TEXT -> PlaybackEngineTrackKind.SUBTITLE
                else -> null
            } ?: return@forEachIndexed

            for (trackIndex in 0 until group.length) {
                val id = "${kind.idPrefix}:$groupIndex:$trackIndex"
                val format = group.getTrackFormat(trackIndex)
                val supported = group.isTrackSupported(trackIndex)
                nextHandles[id] = TrackHandle(
                    kind = kind,
                    mediaTrackGroup = group.mediaTrackGroup,
                    trackIndex = trackIndex,
                    supported = supported,
                )
                descriptors += PlaybackEngineTrack(
                    id = id,
                    kind = kind,
                    labelHint = format.label,
                    language = format.language,
                    codec = format.codecLabel(),
                    channelCount = format.channelCount.takeIf { it > 0 },
                    role = roleLabel(format.roleFlags),
                    supported = supported,
                    selected = group.isTrackSelected(trackIndex),
                )
            }
        }

        trackHandles = nextHandles
        eventListener?.invoke(
            PlaybackEngineEvent(
                mediaRevision = revision,
                tracks = PlaybackEngineTracks(descriptors),
            ),
        )
    }

    private fun classifyFailure(error: PlaybackException): PlaybackEngineFailureClass =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> PlaybackEngineFailureClass.DECODER_OR_FORMAT

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            -> PlaybackEngineFailureClass.AUDIO

            else -> PlaybackEngineFailureClass.OTHER
        }

    private fun Format.codecLabel(): String? =
        codecs
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: sampleMimeType
                ?.substringAfter('/')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private fun roleLabel(roleFlags: Int): String? = when {
        roleFlags and C.ROLE_FLAG_COMMENTARY != 0 -> "Commentary"
        roleFlags and C.ROLE_FLAG_DUB != 0 -> "Dub"
        roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 -> "Audio description"
        roleFlags and C.ROLE_FLAG_CAPTION != 0 -> "Caption"
        roleFlags and C.ROLE_FLAG_SUBTITLE != 0 -> "Subtitle"
        roleFlags and C.ROLE_FLAG_ALTERNATE != 0 -> "Alternate"
        else -> null
    }

    private data class TrackHandle(
        val kind: PlaybackEngineTrackKind,
        val mediaTrackGroup: TrackGroup,
        val trackIndex: Int,
        val supported: Boolean,
    )

    private val PlaybackEngineTrackKind.media3TrackType: Int
        get() = when (this) {
            PlaybackEngineTrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
            PlaybackEngineTrackKind.SUBTITLE -> C.TRACK_TYPE_TEXT
        }

    private val PlaybackEngineTrackKind.idPrefix: String
        get() = when (this) {
            PlaybackEngineTrackKind.AUDIO -> "audio"
            PlaybackEngineTrackKind.SUBTITLE -> "subtitle"
        }
}

internal class Media3PlaybackEngineAdapter(
    private val engine: Media3PlaybackEngine,
) : PlaybackEngine, PlaybackProgressEngine {
    override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
        engine.setEventListener(listener)
    }

    override fun replace(media: PreparedPlaybackMedia): Long = engine.replace(media)

    override fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
        engine.selectAudioTrack(trackId)

    override fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
        engine.selectSubtitleTrack(trackId)

    override fun play(): Boolean = engine.play()

    override fun pause(): Boolean = engine.pause()

    override fun setVolume(volume: Float): Boolean = engine.setVolume(volume)

    override fun setSpeed(speed: Float): Boolean = engine.setSpeed(speed)

    override fun positionSnapshot(): PlaybackPositionSnapshot? = engine.positionSnapshot()

    override fun seekTo(positionMs: Long): Boolean = engine.seekTo(positionMs)

    override fun clear() {
        engine.clear()
    }

    override fun release() {
        engine.release()
    }
}
