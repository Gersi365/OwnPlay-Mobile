package app.ownplay.player.playback

import app.ownplay.player.testing.sourceBlockAfter
import app.ownplay.player.testing.sourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandPresentationLifecycleRegressionTest {
    @Test
    fun mobileAndTvShellsRestoreOnDemandRouteFromProcessSession() {
        val mobile = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")
        val tv = sourceText("src/tv/java/app/ownplay/player/ui/TVOwnPlayApp.kt")

        listOf(mobile, tv).forEach { shell ->
            assertTrue(shell.contains("runtime.onDemandPresentationSession.state.collectAsState()"))
            assertTrue(shell.contains("OnDemandContentKind.MOVIE"))
            assertTrue(shell.contains("OnDemandContentKind.SERIES"))
            assertTrue(shell.contains("val vodFullscreen = onDemandPresentation.isMoviePlayback"))
            assertTrue(shell.contains("val seriesFullscreen = onDemandPresentation.isSeriesPlayback"))
            assertFalse(shell.contains("var vodFullscreen by remember"))
            assertFalse(shell.contains("var seriesFullscreen by remember"))
            assertTrue(shell.contains("runtime.onDemandPresentationSession.showMovieDetail("))
            assertTrue(shell.contains("runtime.onDemandPresentationSession.showSeriesDetail("))
        }
    }

    @Test
    fun vodPlaybackStopsOnlyOnExplicitExitAndDoesNotRewindPreservedPlayer() {
        val vod = sourceText("src/main/java/app/ownplay/player/ui/vod/VodRoute.kt")

        assertFalse(vod.contains("var playingMovie by remember"))
        assertTrue(vod.contains("runtime.onDemandPresentationSession.showMoviePlayback("))
        assertTrue(vod.contains("runtime.onDemandPresentationSession.returnFromMoviePlayback()"))

        val exitBlock = sourceBlockAfter(vod, "fun exitPlayback()")
        assertTrue(exitBlock.contains("runtime.playbackController.stopIfCurrent("))
        assertTrue(exitBlock.contains("onFullscreenStateChanged(false)"))

        val disposableEffect = sourceBlockAfter(
            vod,
            "DisposableEffect(movie.movieId, backOwner)",
        )
        val disposeBlock = sourceBlockAfter(disposableEffect, "onDispose")
        assertFalse(disposeBlock.contains("stopIfCurrent"))
        assertFalse(disposeBlock.contains("onFullscreenStateChanged(false)"))

        assertTrue(vod.contains("val player = playerView?.player ?: return@LaunchedEffect"))
        assertTrue(vod.contains("player.currentPosition < 1_000L"))
        assertTrue(vod.contains("currentPosition = player.currentPosition.coerceAtLeast(0L)"))
    }

    @Test
    fun seriesPlaybackAndDrilldownUseProcessSessionWithoutDisposeStop() {
        val series = sourceText("src/main/java/app/ownplay/player/ui/series/SeriesRoute.kt")

        assertFalse(series.contains("var playingEpisode by remember"))
        assertFalse(series.contains("var playbackReturnsToCatalog by remember"))
        assertTrue(series.contains("runtime.onDemandPresentationSession.showSeriesPlayback("))
        assertTrue(series.contains("runtime.onDemandPresentationSession.returnFromSeriesPlayback()"))
        assertTrue(series.contains("runtime.onDemandPresentationSession.updateSeriesSelection("))
        assertTrue(series.contains("val loadedDetails = details ?: return@LaunchedEffect"))
        assertTrue(series.contains("loadedDetails.seasons.firstOrNull"))

        val exitBlock = sourceBlockAfter(series, "fun exitPlayback()")
        assertTrue(exitBlock.contains("runtime.playbackController.stopIfCurrent("))
        assertTrue(exitBlock.contains("onFullscreenStateChanged(false)"))

        val disposableEffect = sourceBlockAfter(series, "DisposableEffect(backOwner)")
        val disposeBlock = sourceBlockAfter(disposableEffect, "onDispose")
        assertFalse(disposeBlock.contains("stopIfCurrent"))
        assertFalse(disposeBlock.contains("onFullscreenStateChanged(false)"))
    }

    @Test
    fun onDemandSessionIsTransientProcessMemoryOnly() {
        val session = sourceText("src/main/java/app/ownplay/player/playback/OnDemandPresentationSession.kt")
        val store = sourceText("src/main/java/app/ownplay/player/OnDemandPresentationSessionStore.kt")

        assertTrue(session.contains("MutableStateFlow(OnDemandPresentationState())"))
        assertTrue(store.contains("WeakHashMap<OwnPlayAppRuntime, OnDemandPresentationSession>()"))
        assertFalse(session.contains("DataStore"))
        assertFalse(session.contains("Room"))
        assertFalse(session.contains("SharedPreferences"))
        assertFalse(store.contains("DataStore"))
        assertFalse(store.contains("Room"))
        assertFalse(store.contains("SharedPreferences"))
    }
}
