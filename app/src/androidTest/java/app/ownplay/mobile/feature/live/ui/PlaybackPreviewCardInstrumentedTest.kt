package app.ownplay.mobile.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackPreviewCardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tapShowsTransientControlsAndOnlyExplicitActionEntersFullscreen() {
        var fullscreenCalls = 0
        compose.setContent {
            MaterialTheme {
                val engine = remember { Media3PlaybackEngine(InstrumentationRegistry.getInstrumentation().targetContext) }
                DisposableEffect(engine) { onDispose { engine.release() } }
                PlaybackPreviewCard(
                    channelName = "News",
                    readiness = PlaybackReadiness.PREPARED,
                    playbackEngine = engine,
                    catchUpSupported = true,
                    onFullscreen = { fullscreenCalls += 1 },
                    onRetry = {},
                    onCatchUp = {},
                    videoSurface = { modifier -> Box(modifier.background(Color.Black)) },
                )
            }
        }

        assertEquals(0, compose.onAllNodesWithText("Fullscreen").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription(
            "Preview News. Tap to show or hide controls.",
        ).performClick()
        compose.waitForIdle()
        assertEquals(0, fullscreenCalls)
        assertEquals(1, compose.onAllNodesWithText("News").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Record now").fetchSemanticsNodes().size)
        compose.onNodeWithText("Fullscreen").performClick()
        compose.waitForIdle()
        assertEquals(1, fullscreenCalls)
    }

    @Test
    fun overlayAutoHidesAndUnavailableRetryRemainsVisible() {
        val catchUpCalls = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val engine = remember { Media3PlaybackEngine(InstrumentationRegistry.getInstrumentation().targetContext) }
                DisposableEffect(engine) { onDispose { engine.release() } }
                PlaybackPreviewCard(
                    channelName = "News",
                    readiness = PlaybackReadiness.PREPARED,
                    playbackEngine = engine,
                    catchUpSupported = true,
                    onFullscreen = {},
                    onRetry = {},
                    onCatchUp = { catchUpCalls.intValue += 1 },
                    videoSurface = { modifier -> Box(modifier.background(Color.Black)) },
                )
            }
        }

        compose.onNodeWithContentDescription(
            "Preview News. Tap to show or hide controls.",
        ).performClick()
        compose.mainClock.advanceTimeBy(2_000L)
        compose.onNodeWithText("Catch-up").performClick()
        assertEquals(1, catchUpCalls.intValue)
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Fullscreen").fetchSemanticsNodes().size)
        compose.mainClock.advanceTimeBy(3_100L)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Fullscreen").fetchSemanticsNodes().size)
    }

    @Test
    fun unavailableStateKeepsRetryReachableBeyondOverlayTimeout() {
        var retryCalls = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val engine = remember { Media3PlaybackEngine(InstrumentationRegistry.getInstrumentation().targetContext) }
                DisposableEffect(engine) { onDispose { engine.release() } }
                PlaybackPreviewCard(
                    channelName = "Unavailable channel",
                    readiness = PlaybackReadiness.UNAVAILABLE,
                    playbackEngine = engine,
                    catchUpSupported = false,
                    onFullscreen = {},
                    onRetry = { retryCalls += 1 },
                    videoSurface = { modifier -> Box(modifier.background(Color.Black)) },
                )
            }
        }

        compose.mainClock.advanceTimeBy(8_000L)
        compose.waitForIdle()
        assertEquals(
            1,
            compose.onAllNodesWithText("Playback is unavailable for this channel.").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retryCalls)
    }
}
