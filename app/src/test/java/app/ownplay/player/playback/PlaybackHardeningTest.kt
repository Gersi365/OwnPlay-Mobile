package app.ownplay.player.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHardeningTest {
    @Test
    fun retryPolicyUsesBoundedExponentialBackoff() {
        val policy = PlaybackRetryPolicy(
            maxAutomaticAttempts = 4,
            initialDelayMillis = 100L,
            maxDelayMillis = 250L,
        )

        assertEquals(100L, policy.delayBeforeAttempt(1))
        assertEquals(200L, policy.delayBeforeAttempt(2))
        assertEquals(250L, policy.delayBeforeAttempt(3))
        assertEquals(250L, policy.delayBeforeAttempt(4))

        val saturating = PlaybackRetryPolicy(
            maxAutomaticAttempts = 2,
            initialDelayMillis = Long.MAX_VALUE - 1L,
            maxDelayMillis = Long.MAX_VALUE,
        )
        assertEquals(Long.MAX_VALUE, saturating.delayBeforeAttempt(2))
    }

    @Test
    fun recoverableFailureRetriesOnlyWithinAutomaticBudget() = runBlocking {
        val engine = FakeEngine()
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 2,
                initialDelayMillis = 5L,
                maxDelayMillis = 5L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        controller.start(request("one"))
        engine.emitReady()

        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        delay(10L)
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        delay(10L)
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        delay(15L)

        assertEquals(3, resolveCount)
        assertTrue(controller.state.value is PlaybackState.Failed)
        controller.close()
    }

    @Test
    fun readyStateDoesNotResetAutomaticRetryBudget() = runBlocking {
        val engine = FakeEngine()
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 1,
                initialDelayMillis = 5L,
                maxDelayMillis = 5L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        controller.start(request("bounded"))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        delay(10L)
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        delay(15L)

        assertEquals(2, resolveCount)
        assertTrue(controller.state.value is PlaybackState.Failed)
        controller.close()
    }

    @Test
    fun offlineStartWaitsForNetworkBeforeResolving() = runBlocking {
        val engine = FakeEngine()
        val network = MutableStateFlow(PlaybackNetworkState.UNAVAILABLE)
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            networkState = network,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 1,
                initialDelayMillis = 5L,
                maxDelayMillis = 5L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        controller.start(request("offline"))

        val failed = controller.state.value as PlaybackState.Failed
        assertEquals(PlaybackFailureCategory.NETWORK_UNAVAILABLE, failed.failure.category)
        assertEquals(0, resolveCount)

        network.value = PlaybackNetworkState.AVAILABLE
        delay(10L)

        assertEquals(1, resolveCount)
        assertTrue(controller.state.value is PlaybackState.Loading)
        controller.close()
    }

    @Test
    fun unsupportedMediaNeverAutoRetriesAndManualRetryIsNoOp() = runBlocking {
        val engine = FakeEngine()
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 2,
                initialDelayMillis = 1L,
                maxDelayMillis = 1L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        controller.start(request("unsupported"))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.UNSUPPORTED_MEDIA))
        delay(10L)
        controller.retry()
        delay(5L)

        assertEquals(1, resolveCount)
        assertTrue(controller.state.value is PlaybackState.Failed)
        controller.close()
    }

    @Test
    fun networkLossStopsPlaybackAndRecoveryRetriesSameOpaqueRequest() = runBlocking {
        val engine = FakeEngine()
        val network = MutableStateFlow(PlaybackNetworkState.AVAILABLE)
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            networkState = network,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 2,
                initialDelayMillis = 5L,
                maxDelayMillis = 5L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        val request = request("network")
        controller.start(request)
        engine.emitReady()
        assertTrue(controller.state.value is PlaybackState.Playing)

        network.value = PlaybackNetworkState.UNAVAILABLE
        val failed = controller.state.value as PlaybackState.Failed
        assertEquals(PlaybackFailureCategory.NETWORK_UNAVAILABLE, failed.failure.category)
        assertEquals(request, failed.request)

        network.value = PlaybackNetworkState.AVAILABLE
        delay(10L)

        assertEquals(2, resolveCount)
        assertTrue(controller.state.value is PlaybackState.Loading)
        assertFalse(controller.state.value.toString().contains("https://"))
        controller.close()
    }

    @Test
    fun staleEngineFailureDuringRapidSwitchCannotFailNewResolution() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(
            engine = engine,
            resolver = { request ->
                if (request.channelId == "slow") {
                    delay(40L)
                }
                PlaybackResolutionResult.Success(
                    ResolvedPlaybackLocator(
                        "https://stream.example.test/${request.channelId}",
                        ResolvedPlaybackOrigin.DIRECT,
                    ),
                )
            },
        )

        controller.start(request("first"))
        engine.emitReady()
        controller.start(request("slow"))

        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.STREAM_UNAVAILABLE))
        assertTrue(controller.state.value is PlaybackState.Loading)

        controller.start(request("fast"))
        delay(5L)

        assertEquals("fast", engine.lastPreparedChannelHint)
        assertTrue(controller.state.value is PlaybackState.Loading)
        controller.close()
    }

    @Test
    fun slowResolutionFromOldChannelCannotPrepareAfterFastSwitch() = runBlocking {
        val engine = FakeEngine()
        val controller = controller(
            engine = engine,
            resolver = { request ->
                if (request.channelId == "slow") {
                    delay(40L)
                    PlaybackResolutionResult.Success(
                        ResolvedPlaybackLocator(
                            "https://stream.example.test/slow",
                            ResolvedPlaybackOrigin.DIRECT,
                        ),
                    )
                } else {
                    PlaybackResolutionResult.Success(
                        ResolvedPlaybackLocator(
                            "https://stream.example.test/fast",
                            ResolvedPlaybackOrigin.DIRECT,
                        ),
                    )
                }
            },
        )

        controller.start(request("slow"))
        controller.start(request("fast"))
        delay(60L)

        assertEquals(
            listOf("https://stream.example.test/fast"),
            engine.preparedLocators,
        )
        controller.close()
    }

    @Test
    fun closeCancelsPendingAutomaticRetryAndIsIdempotent() = runBlocking {
        val engine = FakeEngine()
        var resolveCount = 0
        val controller = controller(
            engine = engine,
            retryPolicy = PlaybackRetryPolicy(
                maxAutomaticAttempts = 2,
                initialDelayMillis = 30L,
                maxDelayMillis = 30L,
            ),
            resolver = {
                resolveCount += 1
                successLocator()
            },
        )

        controller.start(request("close"))
        engine.emitReady()
        engine.emitFailure(PlaybackFailure(PlaybackFailureCategory.TIMEOUT))
        controller.close()
        controller.close()
        delay(50L)

        assertEquals(1, resolveCount)
        assertEquals(1, engine.releaseCount)
        assertTrue(controller.state.value is PlaybackState.Idle)
    }

    private fun controller(
        engine: FakeEngine,
        networkState: MutableStateFlow<PlaybackNetworkState>? = null,
        retryPolicy: PlaybackRetryPolicy = PlaybackRetryPolicy(maxAutomaticAttempts = 0),
        resolver: suspend (PlaybackRequest) -> PlaybackResolutionResult,
    ) = PlaybackController(
        resolveLocator = resolver,
        engine = engine,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        loadingTimeoutMillis = 5_000L,
        retryPolicy = retryPolicy,
        networkState = networkState,
    )

    private fun request(channelId: String) = PlaybackRequest(
        sourceId = "source",
        channelId = channelId,
    )

    private fun successLocator() = PlaybackResolutionResult.Success(
        ResolvedPlaybackLocator(
            "https://stream.example.test/live",
            ResolvedPlaybackOrigin.DIRECT,
        ),
    )

    private class FakeEngine : PlaybackEngine {
        private var listener: PlaybackEngine.Listener? = null
        val preparedLocators = mutableListOf<String>()
        var releaseCount = 0
            private set
        var stopCount = 0
            private set

        val lastPreparedChannelHint: String?
            get() = preparedLocators.lastOrNull()?.substringAfterLast('/')

        override fun setListener(listener: PlaybackEngine.Listener?) {
            this.listener = listener
        }

        override fun prepare(locator: ResolvedPlaybackLocator) {
            preparedLocators += locator.value
        }

        override fun play() = Unit
        override fun pause() = Unit

        override fun stop() {
            stopCount += 1
        }

        override fun release() {
            releaseCount += 1
        }

        fun emitReady() = listener?.onReady()
        fun emitFailure(failure: PlaybackFailure) = listener?.onFailure(failure)
    }
}
