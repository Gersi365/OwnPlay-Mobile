package app.ownplay.mobile.feature.playback.domain

import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.feature.live.domain.LiveCapacityCoordinator
import app.ownplay.mobile.feature.live.domain.LiveCapacityMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionControllerTest {
    @Test
    fun userPlaybackPreemptsRecordingAndKeepsItsLeaseThroughPip() = runBlocking {
        val capacity = LiveCapacityCoordinator(object : LiveCapacityMetadata {
            override fun accountKey(sourceId: String) = "opaque-account"
            override fun maxConnections(sourceId: String) = 1
        })
        assertTrue(capacity.acquireRecording("source-a", "recording-a", "other", 1L).allowed)
        val preempted = mutableListOf<String>()
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            sourceResolver = FakeResolver(),
            mediaPreparer = FakePreparer(),
            playbackEngine = engine,
            liveCapacityCoordinator = capacity,
            onRecordingPreemptedByPlayback = {
                preempted.addAll(it)
                true
            },
        )
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "watch")

        controller.activateLiveChannel(target)
        engine.emitReady()
        controller.enterPictureInPicture()

        assertEquals(listOf("recording-a"), preempted)
        assertEquals(1, capacity.activeLeaseCount("source-a"))
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.clear()
        assertEquals(0, capacity.activeLeaseCount("source-a"))
    }

    @Test
    fun providerCatchUpReservesCapacityBeforeResolvingStream() = runBlocking {
        val capacity = capacityLimitOne()
        assertTrue(capacity.acquireRecording("source-a", "recording-a", "channel-a", 1L).allowed)
        var leasesAtResolve = 0
        val preempted = mutableListOf<String>()
        val catchUpResolver = object : CatchUpPlaybackMediaResolver {
            override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia {
                leasesAtResolve = capacity.activeLeaseCount("source-a")
                return PreparedPlaybackMedia(
                    uri = "https://provider.example/catchup/${target.programId}",
                    usesProviderConnection = true,
                )
            }
        }
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            sourceResolver = FakeResolver(),
            mediaPreparer = FakePreparer(),
            playbackEngine = engine,
            catchUpMediaResolver = catchUpResolver,
            liveCapacityCoordinator = capacity,
            onRecordingPreemptedByPlayback = { ids ->
                preempted += ids
                true
            },
        )
        val target = PlaybackTarget.CatchUp(
            sourceId = SourceId("source-a"),
            channelId = "channel-a",
            programId = "program-a",
            title = "Program A",
            startEpochSeconds = 1_700_000_000L,
            endEpochSeconds = 1_700_003_600L,
        )

        controller.activateCatchUp(target)

        assertEquals(listOf("recording-a"), preempted)
        assertEquals(1, leasesAtResolve)
        assertEquals(1, capacity.activeLeaseCount("source-a"))
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun typedTargetsKeepLiveMovieAndEpisodeIdentityDistinct() {
        val sourceId = SourceId("source-a")
        val targets: List<PlaybackTarget> = listOf(
            PlaybackTarget.LiveChannel(sourceId, "shared-id"),
            PlaybackTarget.Movie(sourceId, "shared-id"),
            PlaybackTarget.Episode(sourceId, "shared-id"),
        )

        assertEquals(3, targets.toSet().size)
        assertEquals("shared-id", (targets[0] as PlaybackTarget.LiveChannel).channelId)
        assertEquals("shared-id", (targets[1] as PlaybackTarget.Movie).movieId)
        assertEquals("shared-id", (targets[2] as PlaybackTarget.Episode).episodeId)
    }

    @Test
    fun fullscreenChannelSwitchReplacesMediaWithoutLeavingFullscreen() = runBlocking {
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = PlaybackSessionController(resolver, FakePreparer(), engine)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        engine.emitReady()
        controller.enterFullscreen()
        controller.activateLiveChannel(second)

        assertEquals(second, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)
        assertEquals(listOf(first, second), resolver.resolvedTargets)
        assertEquals(2, engine.replacedMedia.size)

        engine.emitReady()
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
    }

    @Test
    fun previewChannelSwitchRemainsInPreview() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        engine.emitReady()
        controller.activateLiveChannel(second)

        assertEquals(second, controller.state.value.target)
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)
    }

    @Test
    fun samePreviewedChannelActivationIsIdempotentWithoutReplacingAgain() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        controller.activateLiveChannel(target)

        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun fullscreenAndPreviewTransitionsPreserveLiveTargetAndMedia() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)

        controller.enterFullscreen()
        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)

        controller.returnToPreview()
        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun engineEventsDriveReadinessAndIgnoreStaleRevision() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        val firstRevision = engine.activeRevision
        controller.activateLiveChannel(second)
        val secondRevision = engine.activeRevision

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.READY,
            revision = firstRevision,
        )
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.READY,
            revision = secondRevision,
        )
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.PREPARING,
            revision = secondRevision,
        )
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            revision = secondRevision,
            failureClass = PlaybackEngineFailureClass.OTHER,
        )
        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
    }

    @Test
    fun staleTrackEventsCannotOverwriteCurrentTargetTracks() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        val firstRevision = engine.activeRevision
        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b"))
        val secondRevision = engine.activeRevision

        engine.emitTracks(
            revision = firstRevision,
            tracks = listOf(audioTrack("old-audio", selected = true)),
        )
        assertTrue(controller.state.value.tracks.audioTracks.isEmpty())

        engine.emitTracks(
            revision = secondRevision,
            tracks = listOf(audioTrack("current-audio", selected = true)),
        )
        assertEquals(
            listOf("current-audio"),
            controller.state.value.tracks.audioTracks.map { it.id },
        )
        assertEquals(
            "current-audio",
            controller.state.value.tracks.selectedAudioTrackId,
        )
    }

    @Test
    fun trackStateIsSecretFreeAndSelectionFailureIsSafe() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        engine.emitTracks(
            tracks = listOf(
                PlaybackEngineTrack(
                    id = "audio:0:0",
                    kind = PlaybackEngineTrackKind.AUDIO,
                    labelHint = null,
                    language = "en",
                    codec = "aac",
                    channelCount = 2,
                    role = "Commentary",
                    supported = true,
                    selected = true,
                ),
                PlaybackEngineTrack(
                    id = "audio:1:0",
                    kind = PlaybackEngineTrackKind.AUDIO,
                    labelHint = "Provider alt",
                    language = "it",
                    codec = "ac3",
                    channelCount = 6,
                    role = null,
                    supported = false,
                    selected = false,
                ),
                PlaybackEngineTrack(
                    id = "subtitle:2:0",
                    kind = PlaybackEngineTrackKind.SUBTITLE,
                    labelHint = null,
                    language = "sq",
                    codec = "vtt",
                    channelCount = null,
                    role = "Subtitle",
                    supported = true,
                    selected = false,
                ),
            ),
        )

        val state = controller.state.value
        assertEquals("En · Commentary · Stereo", state.tracks.audioTracks.first().label)
        assertEquals("audio:0:0", state.tracks.selectedAudioTrackId)
        assertEquals(1, state.tracks.subtitleTracks.size)

        engine.audioSelectionResult = PlaybackEngineSelectionResult.UNSUPPORTED
        assertFalse(controller.selectAudioTrack("audio:1:0"))
        assertEquals(
            PlaybackTrackSelectionIssue.AUDIO_UNSUPPORTED,
            controller.state.value.tracks.selectionIssue,
        )

        engine.subtitleSelectionResult = PlaybackEngineSelectionResult.APPLIED
        assertTrue(controller.selectSubtitleTrack(null))
        assertNull(controller.state.value.tracks.selectionIssue)
    }

    @Test
    fun selectedAudioTrackIsPresentedBeforeOtherProviderOrderedTracks() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        engine.emitTracks(
            tracks = listOf(
                audioTrack("audio-default-later", selected = false),
                audioTrack("audio-selected", selected = true),
                audioTrack("audio-third", selected = false),
            ),
        )

        assertEquals(
            listOf("audio-selected", "audio-default-later", "audio-third"),
            controller.state.value.tracks.audioTracks.map { it.id },
        )
    }

    @Test
    fun playPauseControlsPreserveExplicitUserIntent() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        assertTrue(controller.state.value.playWhenReady)

        assertTrue(controller.pause())
        assertFalse(controller.state.value.playWhenReady)
        assertEquals(1, engine.pauseCalls)

        assertTrue(controller.play())
        assertTrue(controller.state.value.playWhenReady)
        assertEquals(1, engine.playCalls)

        controller.clear()
        assertFalse(controller.play())
        assertFalse(controller.pause())
    }

    @Test
    fun retryActiveTargetReResolvesSameUnavailableChannelWithoutChangingPresentation() = runBlocking {
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = PlaybackSessionController(resolver, FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        resolver.unavailableChannelId = "channel-a"
        controller.activateLiveChannel(target)
        controller.enterFullscreen()
        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)

        resolver.unavailableChannelId = null
        assertTrue(controller.retryActiveTarget())
        assertEquals(2, resolver.resolvedTargets.size)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)

        engine.emitReady()
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
    }

    @Test
    fun decoderFailureUsesExplicitFallbackOnceAndNeverLoops() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            FakeResolver(),
            FakePreparer(fallbackChannelId = "channel-a"),
            engine,
        )

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        val primaryRevision = engine.activeRevision

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            revision = primaryRevision,
            failureClass = PlaybackEngineFailureClass.DECODER_OR_FORMAT,
        )

        assertEquals(2, engine.replacedMedia.size)
        assertTrue(controller.state.value.fallback.attempted)
        assertTrue(controller.state.value.fallback.active)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)

        val fallbackRevision = engine.activeRevision
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            revision = fallbackRevision,
            failureClass = PlaybackEngineFailureClass.DECODER_OR_FORMAT,
        )

        assertEquals(2, engine.replacedMedia.size)
        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertFalse(controller.state.value.fallback.active)
    }

    @Test
    fun transportFailureUsesExplicitFallbackOnceAndNeverLoops() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            FakeResolver(),
            FakePreparer(fallbackChannelId = "channel-a"),
            engine,
        )

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        val primaryRevision = engine.activeRevision

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            revision = primaryRevision,
            failureClass = PlaybackEngineFailureClass.OTHER,
        )

        assertEquals(2, engine.replacedMedia.size)
        assertTrue(controller.state.value.fallback.attempted)
        assertTrue(controller.state.value.fallback.active)
        assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)

        val fallbackRevision = engine.activeRevision
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            revision = fallbackRevision,
            failureClass = PlaybackEngineFailureClass.OTHER,
        )

        assertEquals(2, engine.replacedMedia.size)
        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertFalse(controller.state.value.fallback.active)
    }

    @Test
    fun failedAudioSelectionMayUseExplicitFallbackButUnsupportedSelectionDoesNot() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            FakeResolver(),
            FakePreparer(fallbackChannelId = "channel-a"),
            engine,
        )

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))

        engine.audioSelectionResult = PlaybackEngineSelectionResult.UNSUPPORTED
        assertFalse(controller.selectAudioTrack("audio:9:9"))
        assertEquals(1, engine.replacedMedia.size)

        engine.audioSelectionResult = PlaybackEngineSelectionResult.FAILED
        assertFalse(controller.selectAudioTrack("audio:0:0"))
        assertEquals(2, engine.replacedMedia.size)
        assertTrue(controller.state.value.fallback.attempted)
    }

    @Test
    fun prolongedBufferingFallbackIsBoundedToOneAttempt() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            FakeResolver(),
            FakePreparer(fallbackChannelId = "channel-a"),
            engine,
        )

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        controller.reportProlongedBuffering()
        controller.reportProlongedBuffering()

        assertEquals(2, engine.replacedMedia.size)
        assertTrue(controller.state.value.fallback.attempted)
    }

    @Test
    fun pictureInPictureRequiresPreparedStateAndRestoresFullscreenOrigin() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        controller.enterFullscreen()
        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)

        engine.emitReady()
        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(false)
        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun livePreviewPictureInPictureExitReturnsToPreviewWithoutReplacingMedia() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(false)
        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun catchUpPictureInPictureExitReturnsToPreviewWithoutReplacingMedia() = runBlocking {
        val engine = FakeEngine()
        val catchUpResolver = object : CatchUpPlaybackMediaResolver {
            override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia =
                PreparedPlaybackMedia("https://provider.example/catchup/${target.programId}")
        }
        val controller = PlaybackSessionController(
            sourceResolver = FakeResolver(),
            mediaPreparer = FakePreparer(),
            playbackEngine = engine,
            catchUpMediaResolver = catchUpResolver,
        )
        val target = PlaybackTarget.CatchUp(
            sourceId = SourceId("source-a"),
            channelId = "channel-a",
            programId = "program-a",
            title = "Program A",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
        )

        controller.activateCatchUp(target)
        engine.emitReady()
        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(false)

        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.PREVIEW, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun catchUpFullscreenPictureInPictureExitRestoresFullscreenWithoutReplacingMedia() = runBlocking {
        val engine = FakeEngine()
        val catchUpResolver = object : CatchUpPlaybackMediaResolver {
            override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia =
                PreparedPlaybackMedia("https://provider.example/catchup/${target.programId}")
        }
        val controller = PlaybackSessionController(
            sourceResolver = FakeResolver(),
            mediaPreparer = FakePreparer(),
            playbackEngine = engine,
            catchUpMediaResolver = catchUpResolver,
        )
        val target = PlaybackTarget.CatchUp(
            sourceId = SourceId("source-a"),
            channelId = "channel-a",
            programId = "program-a",
            title = "Program A",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
        )

        controller.activateCatchUp(target)
        engine.emitReady()
        controller.enterFullscreen()
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(true)
        assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)

        controller.onPictureInPictureModeChanged(false)

        assertEquals(target, controller.state.value.target)
        assertEquals(PlaybackPresentation.FULLSCREEN, controller.state.value.presentation)
        assertEquals(1, engine.replacedMedia.size)
    }

    @Test
    fun activeSourceMismatchClearsOwnedSession() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        controller.reconcileActiveSource(SourceId("source-a"))
        assertEquals(target, controller.state.value.target)

        controller.reconcileActiveSource(SourceId("source-b"))
        assertNull(controller.state.value.target)
        assertEquals(PlaybackReadiness.IDLE, controller.state.value.readiness)
        assertEquals(2, engine.clearCount)
    }

    @Test
    fun revalidationClearsTargetThatNoLongerResolves() = runBlocking {
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = PlaybackSessionController(resolver, FakePreparer(), engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        resolver.unavailableChannelId = "channel-a"
        controller.revalidateActiveTarget()

        assertNull(controller.state.value.target)
        assertEquals(PlaybackReadiness.IDLE, controller.state.value.readiness)
        assertEquals(2, engine.clearCount)
    }

    @Test
    fun unavailablePreparationDoesNotLeavePriorMediaOwnedByEngine() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(
            FakeResolver(),
            FakePreparer(unavailableChannelId = "channel-b"),
            engine,
        )

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        engine.emitReady()
        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b"))

        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertEquals(1, engine.replacedMedia.size)
        assertEquals(2, engine.clearCount)
    }

    @Test
    fun engineReplaceFailureMapsToUnavailableAndClearsEngine() = runBlocking {
        val engine = FakeEngine(failOnReplace = true)
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))

        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertEquals(2, engine.clearCount)
    }

    @Test
    fun releaseResetsSessionAndReleasesEngine() = runBlocking {
        val engine = FakeEngine()
        val controller = PlaybackSessionController(FakeResolver(), FakePreparer(), engine)

        controller.activateLiveChannel(PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a"))
        controller.release()

        assertNull(controller.state.value.target)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun repeatedDirectLiveDisconnectsReResolveSameTargetAndKeepOnePipLease() = runBlocking {
        val capacity = capacityLimitOne()
        assertTrue(capacity.acquireRecording("source-a", "recording-a", "channel-a", 1L).allowed)
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val preempted = mutableListOf<String>()
        val controller = autoReconnectController(
            resolver = resolver,
            engine = engine,
            capacity = capacity,
            onPreempted = { ids ->
                preempted.addAll(ids)
                true
            },
        )
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        controller.enterPictureInPicture()

        repeat(3) {
            engine.emitReadiness(
                readiness = PlaybackEngineReadiness.FAILED,
                failureClass = PlaybackEngineFailureClass.OTHER,
                recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
            )
            assertEquals(1, capacity.activeLeaseCount("source-a"))
            assertEquals(PlaybackPresentation.PICTURE_IN_PICTURE, controller.state.value.presentation)
            assertEquals(PlaybackReadiness.PREPARING, controller.state.value.readiness)
            engine.emitReady()
        }

        assertEquals(4, resolver.resolvedTargets.size)
        assertEquals(4, engine.replacedMedia.size)
        assertEquals(1, capacity.activeLeaseCount("source-a"))
        assertEquals(listOf("recording-a"), preempted)
        assertTrue(engine.replacedMedia.map { it.uri }.distinct().single().contains("channel-a"))

        controller.clear()
        assertEquals(0, capacity.activeLeaseCount("source-a"))
    }

    @Test
    fun liveReconnectAcceptsFreshResolverUrlAndDoesNotAssumeAProviderRenewalContract() = runBlocking {
        val resolver = FakeResolver().apply {
            streamLocators = listOf(
                "https://provider.example/live/expired",
                "https://provider.example/live/fresh",
            )
        }
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver = resolver, engine = engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            failureClass = PlaybackEngineFailureClass.OTHER,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_PROVIDER,
        )

        assertEquals(
            listOf(
                "https://provider.example/live/expired",
                "https://provider.example/live/fresh",
            ),
            engine.replacedMedia.map { it.uri },
        )
        assertEquals(2, resolver.resolvedTargets.size)
        engine.emitReady()
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
    }

    @Test
    fun retryBudgetResetsOnlyOnReadyAndExhaustionIsTerminalOnce() = runBlocking {
        val capacity = capacityLimitOne()
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver, engine, capacity)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        repeat(LiveReconnectPolicy.MAX_RETRIES + 1) {
            engine.emitReadiness(
                readiness = PlaybackEngineReadiness.FAILED,
                failureClass = PlaybackEngineFailureClass.OTHER,
                recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
            )
        }

        assertEquals(1 + LiveReconnectPolicy.MAX_RETRIES, resolver.resolvedTargets.size)
        assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
        assertEquals(0, capacity.activeLeaseCount("source-a"))
        val terminalClearCount = engine.clearCount
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )
        assertEquals(terminalClearCount, engine.clearCount)
        assertEquals(1 + LiveReconnectPolicy.MAX_RETRIES, resolver.resolvedTargets.size)
    }

    @Test
    fun capacityOnePlaybackLeaseSurvivesChannelHandover() = runBlocking {
        val capacity = capacityLimitOne()
        val engine = FakeEngine()
        val controller = autoReconnectController(engine = engine, capacity = capacity)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        engine.emitReady()
        controller.activateLiveChannel(second)

        assertEquals(second, controller.state.value.target)
        assertEquals("channel-b", engine.replacedMedia.last().uri.substringAfterLast('/'))
        assertEquals(1, capacity.activeLeaseCount("source-a"))
        controller.clear()
        assertEquals(0, capacity.activeLeaseCount("source-a"))
    }

    @Test
    fun readyBeginsANewRetryIncidentForLaterDisconnects() = runBlocking {
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver, engine)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReady()
        repeat(3) {
            engine.emitReadiness(
                readiness = PlaybackEngineReadiness.FAILED,
                failureClass = PlaybackEngineFailureClass.OTHER,
                recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
            )
            engine.emitReady()
        }

        assertEquals(4, resolver.resolvedTargets.size)
        assertEquals(PlaybackReadiness.PREPARED, controller.state.value.readiness)
    }

    @Test
    fun authAndInvalidSourceFailuresDoNotRetryOrUseFormatFallback() = runBlocking {
        listOf(
            PlaybackEngineRecoveryKind.NON_RETRYABLE_AUTH,
            PlaybackEngineRecoveryKind.NON_RETRYABLE_SOURCE,
        ).forEach { recoveryKind ->
            val resolver = FakeResolver()
            val engine = FakeEngine()
            val capacity = capacityLimitOne()
            val controller = autoReconnectController(resolver, engine, capacity)
            val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

            controller.activateLiveChannel(target)
            engine.emitReadiness(
                readiness = PlaybackEngineReadiness.FAILED,
                failureClass = PlaybackEngineFailureClass.OTHER,
                recoveryKind = recoveryKind,
            )

            assertEquals(1, resolver.resolvedTargets.size)
            assertEquals(1, engine.replacedMedia.size)
            assertFalse(controller.state.value.fallback.attempted)
            assertEquals(PlaybackReadiness.UNAVAILABLE, controller.state.value.readiness)
            assertEquals(0, capacity.activeLeaseCount("source-a"))
        }
    }

    @Test
    fun channelSwitchAndClearCancelPendingReconnectBeforeStaleSourceInstallation() = runBlocking {
        val delay = PendingReconnectDelay()
        val resolver = FakeResolver()
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver, engine, delay = delay::await)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )
        assertEquals(1, delay.pendingCount)

        controller.activateLiveChannel(second)
        assertEquals(0, delay.pendingCount)
        assertEquals(listOf(first, second), resolver.resolvedTargets)
        assertEquals(second, controller.state.value.target)
        assertEquals("channel-b", engine.replacedMedia.last().uri.substringAfterLast('/'))

        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )
        assertEquals(1, delay.pendingCount)
        controller.clear()
        assertEquals(0, delay.pendingCount)
        assertEquals(2, resolver.resolvedTargets.size)
        assertNull(controller.state.value.target)
    }

    @Test
    fun staleResolverResultForChannelACannotInstallAfterSwitchToChannelB() = runBlocking {
        val resolver = FakeResolver()
        var cancelledResolver = false
        resolver.resolveOverride = { target ->
            if (resolver.resolvedTargets.size == 2) {
                try {
                    suspendCancellableCoroutine<LivePlaybackSource?> { continuation ->
                        continuation.invokeOnCancellation { cancelledResolver = true }
                    }
                } catch (_: CancellationException) {
                    LivePlaybackSource.Direct("https://provider.example/live/stale-channel-a")
                }
            } else {
                LivePlaybackSource.Direct("https://provider.example/live/${target.channelId}")
            }
        }
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver = resolver, engine = engine)
        val first = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")
        val second = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-b")

        controller.activateLiveChannel(first)
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )
        assertEquals(2, resolver.resolvedTargets.size)

        controller.activateLiveChannel(second)

        assertTrue(cancelledResolver)
        assertEquals(listOf(first, first, second), resolver.resolvedTargets)
        assertEquals(second, controller.state.value.target)
        assertEquals(2, engine.replacedMedia.size)
        assertEquals("channel-b", engine.replacedMedia.last().uri.substringAfterLast('/'))
    }

    @Test
    fun releaseDuringSourceResolutionCancelsReconnectAndReleasesItsLease() = runBlocking {
        val resolver = FakeResolver()
        var cancelledResolver = false
        resolver.resolveOverride = { target ->
            if (resolver.resolvedTargets.size == 1) {
                LivePlaybackSource.Direct("https://provider.example/live/${target.channelId}")
            } else {
                suspendCancellableCoroutine<LivePlaybackSource?> { continuation ->
                    continuation.invokeOnCancellation { cancelledResolver = true }
                }
            }
        }
        val capacity = capacityLimitOne()
        val engine = FakeEngine()
        val controller = autoReconnectController(resolver, engine, capacity)
        val target = PlaybackTarget.LiveChannel(SourceId("source-a"), "channel-a")

        controller.activateLiveChannel(target)
        engine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )
        assertEquals(2, resolver.resolvedTargets.size)

        controller.release()

        assertTrue(cancelledResolver)
        assertEquals(1, engine.releaseCount)
        assertNull(controller.state.value.target)
        assertEquals(0, capacity.activeLeaseCount("source-a"))
    }

    @Test
    fun vodAndOfflineDownloadErrorsDoNotEnterLiveReconnect() = runBlocking {
        var catchUpResolutions = 0
        val catchUpEngine = FakeEngine()
        val catchUpController = autoReconnectController(
            engine = catchUpEngine,
            catchUpResolver = object : CatchUpPlaybackMediaResolver {
                override suspend fun resolve(target: PlaybackTarget.CatchUp): PreparedPlaybackMedia? {
                    catchUpResolutions += 1
                    return PreparedPlaybackMedia(target.localMediaUri!!)
                }
            },
        )
        val catchUp = PlaybackTarget.CatchUp(
            sourceId = SourceId("source-a"),
            channelId = "channel-a",
            programId = "program-a",
            title = "Saved catch-up",
            startEpochSeconds = 100L,
            endEpochSeconds = 200L,
            offlineDownloadId = "download-a",
            localMediaUri = "file:///offline/catchup.mp4",
        )
        catchUpController.activateCatchUp(catchUp)
        catchUpEngine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )

        var libraryResolutions = 0
        val libraryEngine = FakeEngine()
        val libraryController = autoReconnectController(
            engine = libraryEngine,
            libraryResolver = object : LibraryPlaybackMediaResolver {
                override suspend fun resolve(target: PlaybackTarget.Library): PreparedPlaybackMedia? {
                    libraryResolutions += 1
                    return PreparedPlaybackMedia("file:///offline/${target.offlineDownloadId}.mp4")
                }
            },
        )
        libraryController.activateLibraryMedia(
            PlaybackTarget.Movie(SourceId("source-a"), "movie-a", offlineDownloadId = "download-a"),
        )
        libraryEngine.emitReadiness(
            readiness = PlaybackEngineReadiness.FAILED,
            recoveryKind = PlaybackEngineRecoveryKind.RETRYABLE_NETWORK,
        )

        assertEquals(1, catchUpResolutions)
        assertEquals(1, catchUpEngine.replacedMedia.size)
        assertEquals(1, libraryResolutions)
        assertEquals(1, libraryEngine.replacedMedia.size)
    }

    private class FakeResolver : LivePlaybackSourceResolver {
        val resolvedTargets = mutableListOf<PlaybackTarget.LiveChannel>()
        val resolvedStreamLocators = mutableListOf<String>()
        var streamLocators: List<String> = emptyList()
        var unavailableChannelId: String? = null
        var resolveOverride: (suspend (PlaybackTarget.LiveChannel) -> LivePlaybackSource?)? = null

        override suspend fun resolve(target: PlaybackTarget.LiveChannel): LivePlaybackSource? {
            val resolutionIndex = resolvedTargets.size
            resolvedTargets += target
            val defaultLocator = "https://provider.example/live/${target.channelId}"
            val locator = streamLocators.getOrNull(resolutionIndex) ?: defaultLocator
            resolvedStreamLocators += locator
            resolveOverride?.let { return it(target) }
            if (target.channelId == unavailableChannelId) return null
            return LivePlaybackSource.Direct(locator)
        }
    }

    private fun autoReconnectController(
        resolver: FakeResolver = FakeResolver(),
        engine: FakeEngine = FakeEngine(),
        capacity: LiveCapacityCoordinator? = null,
        catchUpResolver: CatchUpPlaybackMediaResolver? = null,
        libraryResolver: LibraryPlaybackMediaResolver? = null,
        delay: suspend (Long) -> Unit = {},
        onPreempted: suspend (List<String>) -> Boolean = { true },
    ): PlaybackSessionController = PlaybackSessionController(
        sourceResolver = resolver,
        mediaPreparer = FakePreparer(),
        playbackEngine = engine,
        libraryMediaResolver = libraryResolver,
        catchUpMediaResolver = catchUpResolver,
        liveCapacityCoordinator = capacity,
        onRecordingPreemptedByPlayback = onPreempted,
        reconnectDispatcher = Dispatchers.Unconfined,
        reconnectDelay = delay,
    )

    private fun capacityLimitOne() = LiveCapacityCoordinator(object : LiveCapacityMetadata {
        override fun accountKey(sourceId: String) = "opaque-account"
        override fun maxConnections(sourceId: String) = 1
    })

    private class PendingReconnectDelay {
        private val pending = mutableListOf<CancellableContinuation<Unit>>()
        val pendingCount: Int get() = pending.size

        suspend fun await(@Suppress("UNUSED_PARAMETER") delayMs: Long) {
            suspendCancellableCoroutine<Unit> { continuation ->
                pending += continuation
                continuation.invokeOnCancellation { pending.remove(continuation) }
            }
        }
    }

    private class FakePreparer(
        private val unavailableChannelId: String? = null,
        private val fallbackChannelId: String? = null,
    ) : LivePlaybackMediaPreparer {
        override fun prepare(source: LivePlaybackSource): PreparedPlaybackMedia? {
            val direct = source as LivePlaybackSource.Direct
            if (unavailableChannelId != null && direct.streamLocator.contains(unavailableChannelId)) {
                return null
            }
            val fallback = if (
                fallbackChannelId != null &&
                direct.streamLocator.contains(fallbackChannelId)
            ) {
                PreparedPlaybackAlternative(
                    uri = "${direct.streamLocator}?format=fallback",
                    mimeType = "application/x-mpegURL",
                )
            } else {
                null
            }
            return PreparedPlaybackMedia(
                uri = direct.streamLocator,
                fallback = fallback,
            )
        }
    }

    private class FakeEngine(
        private val failOnReplace: Boolean = false,
    ) : PlaybackEngine {
        val replacedMedia = mutableListOf<PreparedPlaybackMedia>()
        var clearCount: Int = 0
        var releaseCount: Int = 0
        var playCalls: Int = 0
        var pauseCalls: Int = 0
        var activeRevision: Long = 0L
            private set
        var audioSelectionResult: PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED
        var subtitleSelectionResult: PlaybackEngineSelectionResult =
            PlaybackEngineSelectionResult.APPLIED

        private var nextRevision: Long = 0L
        private var listener: ((PlaybackEngineEvent) -> Unit)? = null

        override fun setEventListener(listener: (PlaybackEngineEvent) -> Unit) {
            this.listener = listener
        }

        override fun replace(media: PreparedPlaybackMedia): Long {
            if (failOnReplace) error("engine failure")
            nextRevision += 1L
            activeRevision = nextRevision
            replacedMedia += media
            return activeRevision
        }

        override fun selectAudioTrack(trackId: String?): PlaybackEngineSelectionResult =
            audioSelectionResult

        override fun selectSubtitleTrack(trackId: String?): PlaybackEngineSelectionResult =
            subtitleSelectionResult

        override fun play(): Boolean {
            playCalls += 1
            return activeRevision != 0L
        }

        override fun pause(): Boolean {
            pauseCalls += 1
            return activeRevision != 0L
        }

        override fun clear() {
            clearCount += 1
        }

        override fun release() {
            releaseCount += 1
        }

        fun emitReady() {
            emitReadiness(
                readiness = PlaybackEngineReadiness.READY,
                revision = activeRevision,
            )
        }

        fun emitReadiness(
            readiness: PlaybackEngineReadiness,
            revision: Long = activeRevision,
            failureClass: PlaybackEngineFailureClass? = null,
            recoveryKind: PlaybackEngineRecoveryKind? = null,
        ) {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    readiness = readiness,
                    failureClass = failureClass,
                    recoveryKind = recoveryKind,
                ),
            )
        }

        fun emitTracks(
            tracks: List<PlaybackEngineTrack>,
            revision: Long = activeRevision,
        ) {
            listener?.invoke(
                PlaybackEngineEvent(
                    mediaRevision = revision,
                    tracks = PlaybackEngineTracks(tracks),
                ),
            )
        }
    }

    private companion object {
        fun audioTrack(
            id: String,
            selected: Boolean,
        ): PlaybackEngineTrack =
            PlaybackEngineTrack(
                id = id,
                kind = PlaybackEngineTrackKind.AUDIO,
                labelHint = null,
                language = null,
                codec = null,
                channelCount = null,
                role = null,
                supported = true,
                selected = selected,
            )
    }
}
