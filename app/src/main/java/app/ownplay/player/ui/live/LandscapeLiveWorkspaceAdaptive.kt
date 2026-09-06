package app.ownplay.player.ui.live

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.epg.EpgSnapshot
import app.ownplay.player.live.LiveBrowseOrder
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.PlaybackNavigationDirection
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.playback.PlaybackVideoOutput
import app.ownplay.player.ui.EpgPanel
import app.ownplay.player.ui.LivePreviewPanel
import app.ownplay.player.ui.view.ContentViewMode

/**
 * Landscape Live shell with one consistent browse model across touch and TV layouts.
 *
 * Live browsing starts at category level. After a category is chosen the established
 * List / Compact / Cards channel browser is shown. Before a channel is selected, browsing owns
 * the full workspace. Once a channel is selected, the browse area remains available on the left
 * while the selected channel owns a dedicated playback/EPG panel on the right.
 *
 * TV keeps Preview presentation-only: it never becomes a remote focus destination. D-pad movement
 * remains native inside the channel browser, including two-dimensional Left/Right/Up/Down movement
 * in Cards view. When no channel card exists farther to the right, normal focus search may enter the
 * focusable EPG panel. Left from EPG restores the last focused channel. A second OK on the same
 * channel is routed by LiveRoute to Fullscreen. Back/ESC precedence remains owned by LiveRoute.
 */
@Composable
internal fun LandscapeLiveWorkspaceAdaptive(
    state: LiveBrowseState,
    hierarchyLevel: LiveBrowseHierarchyLevel,
    preview: LivePlaybackSelection?,
    playbackState: PlaybackState,
    videoOutput: PlaybackVideoOutput,
    epgSnapshot: EpgSnapshot?,
    currentEpgByChannelId: Map<String, EpgProgram>,
    epgLoading: Boolean,
    epgFailed: Boolean,
    viewMode: ContentViewMode,
    onViewModeSelected: (ContentViewMode) -> Unit,
    onSearchChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onFavoritesOnlyChanged: (Boolean) -> Unit,
    onOrderChanged: (LiveBrowseOrder) -> Unit,
    onCustomGroupSelected: (String?) -> Unit,
    onChannelSelected: (String) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onNavigatePreview: (PlaybackNavigationDirection) -> Unit,
    onOpenFullscreen: (LivePlaybackSelection) -> Unit,
    onPreviewClosed: () -> Unit,
    onOpenEpgGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val channelFocusRequester = remember { FocusRequester() }
    var focusChannelId by remember { mutableStateOf<String?>(null) }
    var channelFocusRequestGeneration by remember { mutableIntStateOf(0) }
    var initialBrowserFocusRequested by remember { mutableStateOf(false) }
    var previousPreviewChannelId by remember { mutableStateOf<String?>(null) }
    var lastPreviewChannelId by remember { mutableStateOf<String?>(null) }

    fun requestChannelFocus(preferredChannelId: String?) {
        val visibleTarget = preferredChannelId?.takeIf { candidate ->
            state.channels.any { channel -> channel.channelId == candidate }
        }
        val target = visibleTarget ?: state.channels.firstOrNull()?.channelId ?: return
        focusChannelId = target
        channelFocusRequestGeneration += 1
    }

    LaunchedEffect(
        hierarchyLevel,
        state.channels.firstOrNull()?.channelId,
        preview?.request?.channelId,
    ) {
        if (hierarchyLevel == LiveBrowseHierarchyLevel.CATEGORIES) {
            initialBrowserFocusRequested = false
            focusChannelId = null
            previousPreviewChannelId = preview?.request?.channelId
            return@LaunchedEffect
        }

        val currentPreviewChannelId = preview?.request?.channelId
        if (currentPreviewChannelId != null) {
            lastPreviewChannelId = currentPreviewChannelId
        }
        if (!initialBrowserFocusRequested && state.channels.isNotEmpty()) {
            initialBrowserFocusRequested = true
            requestChannelFocus(currentPreviewChannelId ?: state.channels.first().channelId)
        }
        if (currentPreviewChannelId == null && previousPreviewChannelId != null) {
            requestChannelFocus(previousPreviewChannelId)
        }
        previousPreviewChannelId = currentPreviewChannelId
    }

    if (preview == null) {
        LandscapeBrowseSurface(
            state = state,
            hierarchyLevel = hierarchyLevel,
            playingChannelId = null,
            currentEpgByChannelId = currentEpgByChannelId,
            viewMode = viewMode,
            onViewModeSelected = onViewModeSelected,
            onSearchChange = onSearchChange,
            onCategorySelected = onCategorySelected,
            onFavoritesOnlyChanged = onFavoritesOnlyChanged,
            onOrderChanged = onOrderChanged,
            onCustomGroupSelected = onCustomGroupSelected,
            onChannelSelected = onChannelSelected,
            focusChannelId = focusChannelId,
            focusRequestGeneration = channelFocusRequestGeneration,
            channelFocusRequester = channelFocusRequester,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LandscapeBrowseSurface(
            state = state,
            hierarchyLevel = hierarchyLevel,
            playingChannelId = preview.request.channelId,
            currentEpgByChannelId = currentEpgByChannelId,
            viewMode = viewMode,
            onViewModeSelected = onViewModeSelected,
            onSearchChange = onSearchChange,
            onCategorySelected = onCategorySelected,
            onFavoritesOnlyChanged = onFavoritesOnlyChanged,
            onOrderChanged = onOrderChanged,
            onCustomGroupSelected = onCustomGroupSelected,
            onChannelSelected = onChannelSelected,
            focusChannelId = focusChannelId,
            focusRequestGeneration = channelFocusRequestGeneration,
            channelFocusRequester = channelFocusRequester,
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight(),
        )

        Surface(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LivePreviewPanel(
                    selection = preview,
                    state = playbackState,
                    videoOutput = videoOutput,
                    onPlay = onPlay,
                    onPause = onPause,
                    onRetry = onRetry,
                    onNavigate = onNavigatePreview,
                    onOpenFullscreen = { onOpenFullscreen(preview) },
                    onClose = onPreviewClosed,
                    modifier = Modifier.fillMaxWidth(),
                )

                EpgPanel(
                    snapshot = epgSnapshot,
                    loading = epgLoading,
                    failed = epgFailed,
                    onOpenGuide = onOpenEpgGuide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionLeft &&
                                LandscapeLiveFocusPolicy.destination(
                                    current = LandscapeLiveFocusZone.EPG,
                                    action = LandscapeLiveFocusAction.LEFT,
                                ) == LandscapeLiveFocusZone.BROWSER
                            ) {
                                channelFocusRequester.requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun LandscapeBrowseSurface(
    state: LiveBrowseState,
    hierarchyLevel: LiveBrowseHierarchyLevel,
    playingChannelId: String?,
    currentEpgByChannelId: Map<String, EpgProgram>,
    viewMode: ContentViewMode,
    onViewModeSelected: (ContentViewMode) -> Unit,
    onSearchChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onFavoritesOnlyChanged: (Boolean) -> Unit,
    onOrderChanged: (LiveBrowseOrder) -> Unit,
    onCustomGroupSelected: (String?) -> Unit,
    onChannelSelected: (String) -> Unit,
    focusChannelId: String?,
    focusRequestGeneration: Int,
    channelFocusRequester: FocusRequester,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        tonalElevation = 0.dp,
    ) {
        HierarchicalLiveBrowse(
            state = state,
            hierarchyLevel = hierarchyLevel,
            playingChannelId = playingChannelId,
            currentEpgByChannelId = currentEpgByChannelId,
            viewMode = viewMode,
            onViewModeSelected = onViewModeSelected,
            onSearchChange = onSearchChange,
            onCategorySelected = onCategorySelected,
            onFavoritesOnlyChanged = onFavoritesOnlyChanged,
            onOrderChanged = onOrderChanged,
            onCustomGroupSelected = onCustomGroupSelected,
            onChannelSelected = onChannelSelected,
            focusChannelId = focusChannelId,
            focusRequestGeneration = focusRequestGeneration,
            channelFocusRequester = channelFocusRequester,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

internal enum class LandscapeLiveFocusZone {
    BROWSER,
    EPG,
}

internal enum class LandscapeLiveFocusAction {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    BACK,
}

internal object LandscapeLiveFocusPolicy {
    /**
     * Browser arrows are deliberately left to Compose focus search so Cards keeps native 2D D-pad
     * navigation. Preview is presentation-only and therefore never a focus destination.
     */
    fun destination(
        current: LandscapeLiveFocusZone,
        action: LandscapeLiveFocusAction,
    ): LandscapeLiveFocusZone? = when (current) {
        LandscapeLiveFocusZone.BROWSER -> null
        LandscapeLiveFocusZone.EPG -> when (action) {
            LandscapeLiveFocusAction.LEFT -> LandscapeLiveFocusZone.BROWSER
            else -> null
        }
    }
}
