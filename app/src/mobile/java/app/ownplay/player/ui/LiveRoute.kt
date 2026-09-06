package app.ownplay.player.ui

import androidx.compose.runtime.Composable
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.PlaybackNavigationDirection
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.playback.PlaybackVideoOutput
import app.ownplay.player.source.SourceSyncState

/** Mobile flavor entry point for the rebuilt Live surface. */
@Composable
internal fun LiveRoute(
    runtime: OwnPlayAppRuntime,
    sourceId: String,
    activeSelection: LivePlaybackSelection?,
    playbackState: PlaybackState,
    videoOutput: PlaybackVideoOutput,
    syncState: SourceSyncState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreviewRequested: (LivePlaybackSelection) -> Unit,
    onPreviewClosed: () -> Unit,
    onOpenFullscreen: (LivePlaybackSelection) -> Unit,
    onNavigatePreview: (PlaybackNavigationDirection) -> Unit,
) = TargetLiveRoute(
    runtime = runtime,
    sourceId = sourceId,
    activeSelection = activeSelection,
    playbackState = playbackState,
    videoOutput = videoOutput,
    syncState = syncState,
    onPlay = onPlay,
    onPause = onPause,
    onRetry = onRetry,
    onOpenMovies = onOpenMovies,
    onOpenSeries = onOpenSeries,
    onOpenSettings = onOpenSettings,
    onPreviewRequested = onPreviewRequested,
    onPreviewClosed = onPreviewClosed,
    onOpenFullscreen = onOpenFullscreen,
    onNavigatePreview = onNavigatePreview,
)
