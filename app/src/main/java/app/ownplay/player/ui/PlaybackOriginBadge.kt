package app.ownplay.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ownplay.player.playback.ResolvedPlaybackOrigin

/**
 * Playback origin is runtime metadata and must not become persistent fullscreen chrome.
 *
 * The shell still resolves origin for playback behavior, but active Movie, Series episode, and
 * Offline playback intentionally render no playback-origin badge over video.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun PlaybackOriginBadge(
    origin: ResolvedPlaybackOrigin,
    modifier: Modifier = Modifier,
) = Unit
