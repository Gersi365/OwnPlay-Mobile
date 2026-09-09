package app.ownplay.player.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.epg.EpgSnapshot
import app.ownplay.player.live.LiveBrowseSession
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.live.LiveChannelItem
import app.ownplay.player.live.LiveChannelLogoResolver
import app.ownplay.player.playback.LiveChannelSelectionAction
import app.ownplay.player.playback.LiveChannelSelectionRouter
import app.ownplay.player.playback.LivePlaybackBrowseContext
import app.ownplay.player.playback.LivePlaybackSelection
import app.ownplay.player.playback.PlaybackNavigationDirection
import app.ownplay.player.playback.PlaybackState
import app.ownplay.player.playback.PlaybackVideoOutput
import app.ownplay.player.source.SourceSyncStage
import app.ownplay.player.source.SourceSyncState
import app.ownplay.player.source.network.SourceHttpClient
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.max

private const val MOBILE_LOGO_MAX_BYTES = 2 * 1024 * 1024
private const val MOBILE_LOGO_MAX_EDGE_PX = 256
private const val MOBILE_LOGO_CACHE_ENTRIES = 32
private const val MOBILE_EPG_PREFETCH_STEP = 6
private const val MOBILE_EPG_PREFETCH_WINDOW = 14
private const val MOBILE_EPG_PREFETCH_CONCURRENCY = 4
private const val MOBILE_CATEGORY_SWIPE_TRIGGER_FRACTION = 0.12f

private val mobileChannelLogoCacheLock = Any()
private val mobileChannelLogoMemoryCache = object : LinkedHashMap<String, ImageBitmap>(
    MOBILE_LOGO_CACHE_ENTRIES,
    0.75f,
    true,
) {
    override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, ImageBitmap>?,
    ): Boolean = size > MOBILE_LOGO_CACHE_ENTRIES
}
private val mobileChannelLogoRequestCoordinator =
    MobileChannelLogoRequestCoordinator<ImageBitmap?>()

private sealed interface MobileChannelLogoState {
    data object Loading : MobileChannelLogoState
    data class Loaded(val image: ImageBitmap) : MobileChannelLogoState
    data object Unavailable : MobileChannelLogoState
}

/**
 * Mobile-only Live surface.
 *
 * Categories are filters only. Channel identity is logo + channel name, with current EPG as the
 * only optional secondary line. Provider category names are deliberately never rendered inside a
 * channel row.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun TargetLiveRoute(
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
) {
    val browseSession = remember(sourceId) { LiveBrowseSession() }
    val browseFlow = remember(sourceId) {
        browseSession.observe(runtime.observeLiveCatalog(sourceId))
    }
    val state by browseFlow.collectAsState(initial = LiveBrowseState())
    val scope = rememberCoroutineScope()
    val channelListState = rememberLazyListState()
    val preview = activeSelection?.takeIf { it.request.sourceId == sourceId }
    val epgPrefetchBucket = channelListState.firstVisibleItemIndex / MOBILE_EPG_PREFETCH_STEP

    var searchExpanded by remember(sourceId) { mutableStateOf(false) }
    var epgSnapshot by remember(sourceId, preview?.request?.channelId) {
        mutableStateOf<EpgSnapshot?>(null)
    }
    var currentEpgByChannelId by remember(sourceId) {
        mutableStateOf<Map<String, EpgProgram>>(emptyMap())
    }
    var epgLookupLoading by remember(sourceId, preview?.request?.channelId) {
        mutableStateOf(false)
    }
    var epgLookupFailed by remember(sourceId, preview?.request?.channelId) {
        mutableStateOf(false)
    }
    var showEpgGuide by remember(sourceId) { mutableStateOf(false) }

    val syncForSource = syncState.sourceId == sourceId
    val loadingChannels = syncForSource && syncState.stage == SourceSyncStage.LoadingChannels
    val loadingEpg = syncForSource && syncState.stage == SourceSyncStage.LoadingEpg
    val channelRefreshFailed = syncForSource && syncState.stage == SourceSyncStage.ChannelsFailed
    val epgRefreshFailed = syncForSource && syncState.stage == SourceSyncStage.EpgFailed
    val selectedEpgFailed = epgLookupFailed || (
        epgRefreshFailed && epgSnapshot?.programs.isNullOrEmpty()
    )

    BackHandler(enabled = preview != null) {
        onPreviewClosed()
    }

    LaunchedEffect(sourceId, syncState.sourceId, syncState.stage) {
        if (loadingEpg) return@LaunchedEffect
        while (true) {
            try {
                currentEpgByChannelId = runtime.currentEpgPrograms(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the last known EPG map. Live browsing must remain usable without EPG.
            }
            delay(30_000L)
        }
    }

    LaunchedEffect(sourceId, epgPrefetchBucket, state.channels, loadingEpg) {
        if (loadingEpg || state.channels.isEmpty()) return@LaunchedEffect
        val startIndex = (epgPrefetchBucket * MOBILE_EPG_PREFETCH_STEP)
            .coerceIn(0, state.channels.lastIndex)
        val endExclusive = minOf(
            state.channels.size,
            startIndex + MOBILE_EPG_PREFETCH_WINDOW,
        )
        val missingChannels = state.channels
            .subList(startIndex, endExclusive)
            .filterNot { channel -> currentEpgByChannelId.containsKey(channel.channelId) }

        for (batch in missingChannels.chunked(MOBILE_EPG_PREFETCH_CONCURRENCY)) {
            val loadedPrograms = coroutineScope {
                batch.map { channel ->
                    async {
                        try {
                            channel.channelId to runtime.epgSnapshot(
                                sourceId = sourceId,
                                channelId = channel.channelId,
                            )?.current
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            channel.channelId to null
                        }
                    }
                }.awaitAll()
            }.mapNotNull { (channelId, currentProgram) ->
                currentProgram?.let { channelId to it }
            }.toMap()

            if (loadedPrograms.isNotEmpty()) {
                currentEpgByChannelId = currentEpgByChannelId + loadedPrograms
            }
        }
    }

    LaunchedEffect(preview?.request?.channelId) {
        if (preview != null) {
            searchExpanded = false
            if (state.query.searchTerm.isNotBlank()) {
                browseSession.updateSearch("")
            }
        }
    }

    LaunchedEffect(preview?.request?.channelId, state.channels) {
        val selectedChannelId = preview?.request?.channelId ?: return@LaunchedEffect
        val selectedIndex = state.channels.indexOfFirst { it.channelId == selectedChannelId }
        if (selectedIndex >= 0) {
            channelListState.scrollToItem(selectedIndex)
        }
    }

    LaunchedEffect(preview?.request?.channelId, syncState.sourceId, syncState.stage) {
        showEpgGuide = preview != null && LiveEpgPresentationBridge.consumeFullGuideRequest()
        val selected = preview
        if (selected == null || loadingEpg) {
            epgSnapshot = null
            epgLookupLoading = false
            epgLookupFailed = false
            return@LaunchedEffect
        }
        epgLookupLoading = true
        epgLookupFailed = false
        try {
            epgSnapshot = runtime.epgSnapshot(
                sourceId = sourceId,
                channelId = selected.request.channelId,
            )
            currentEpgByChannelId = runtime.currentEpgPrograms(sourceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            epgSnapshot = null
            epgLookupFailed = true
        } finally {
            epgLookupLoading = false
        }
    }

    fun selectChannel(channelId: String) {
        val currentPreview = preview
        if (currentPreview?.request?.channelId == channelId) {
            onOpenFullscreen(currentPreview)
            return
        }
        val channel = state.channels.firstOrNull { it.channelId == channelId } ?: return
        val browseContext = LivePlaybackBrowseContext.capture(
            sourceId = sourceId,
            visibleChannels = state.channels,
            categories = state.categories,
            categoryNavigationChannels = state.categoryNavigationChannels,
            activeCategoryKey = state.query.categoryKey,
        )
        when (
            val action = LiveChannelSelectionRouter.route(
                channel = channel,
                isEditing = false,
                browseContext = browseContext,
            )
        ) {
            is LiveChannelSelectionAction.StartPlayback -> onPreviewRequested(action.selection)
            is LiveChannelSelectionAction.ToggleEditSelection -> Unit
        }
    }

    val browseContent: @Composable (Modifier) -> Unit = { modifier ->
        MobileLiveBrowsePane(
            state = state,
            playingChannelId = preview?.request?.channelId,
            currentEpgByChannelId = currentEpgByChannelId,
            channelListState = channelListState,
            searchExpanded = searchExpanded,
            onSearchExpandedChange = { searchExpanded = it },
            onSearchChange = browseSession::updateSearch,
            onCategorySelected = browseSession::selectCategory,
            onChannelSelected = ::selectChannel,
            loadingChannels = loadingChannels,
            channelRefreshFailed = channelRefreshFailed,
            onRetry = { scope.launch { runtime.refreshSource(sourceId) } },
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (preview != null) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = preview.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "PREVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                    showLiveBadge = false,
                )
                EpgPanel(
                    snapshot = epgSnapshot,
                    loading = loadingEpg || epgLookupLoading,
                    failed = selectedEpgFailed,
                    onOpenGuide = { showEpgGuide = true },
                )
            }
        }
        browseContent(Modifier.weight(1f))
    }

    if (showEpgGuide && preview != null) {
        EpgGuideSheet(
            channelName = preview.displayName,
            snapshot = epgSnapshot,
            loading = loadingEpg || epgLookupLoading,
            failed = selectedEpgFailed,
            onDismiss = { showEpgGuide = false },
        )
    }
}

@Composable
private fun MobileLiveBrowsePane(
    state: LiveBrowseState,
    playingChannelId: String?,
    currentEpgByChannelId: Map<String, EpgProgram>,
    channelListState: LazyListState,
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onChannelSelected: (String) -> Unit,
    loadingChannels: Boolean,
    channelRefreshFailed: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categoryListState = rememberLazyListState()

    LaunchedEffect(state.categories, state.query.categoryKey) {
        val activeCategoryIndex = state.categories.indexOfFirst { category ->
            category.providerCategoryKey == state.query.categoryKey
        }
        if (activeCategoryIndex >= 0) {
            categoryListState.animateScrollToItem(activeCategoryIndex)
        }
    }

    fun selectAdjacentCategory(totalHorizontalDrag: Float) {
        if (state.categories.size < 2) return
        val currentIndex = state.categories.indexOfFirst { category ->
            category.providerCategoryKey == state.query.categoryKey
        }.takeIf { it >= 0 } ?: -1
        val targetIndex = if (totalHorizontalDrag < 0f) {
            currentIndex + 1
        } else {
            currentIndex - 1
        }
        state.categories.getOrNull(targetIndex)?.let { category ->
            onCategorySelected(category.providerCategoryKey)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        state.channels.isNotEmpty() -> "${state.channels.size} channels"
                        state.catalogChannelCount > 0 -> "Browse channels"
                        else -> "Your channels"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MobileSearchAction(
                searchExpanded = searchExpanded,
                onSearchExpandedChange = onSearchExpandedChange,
                onSearchChange = onSearchChange,
            )
        }

        AnimatedVisibility(
            visible = searchExpanded || state.query.searchTerm.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MobileLiveSearchField(
                value = state.query.searchTerm,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.categories.isNotEmpty()) {
            LazyRow(
                state = categoryListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(
                    items = state.categories,
                    key = { it.providerCategoryKey },
                ) { category ->
                    MobileCategoryTab(
                        label = category.name,
                        selected = state.query.categoryKey == category.providerCategoryKey,
                        onClick = {
                            onCategorySelected(
                                if (state.query.categoryKey == category.providerCategoryKey) {
                                    null
                                } else {
                                    category.providerCategoryKey
                                },
                            )
                        },
                    )
                }
            }
        }

        when {
            loadingChannels && state.catalogChannelCount == 0 -> MobileLiveStatusState(
                title = "Loading channels",
                detail = "Preparing your Live catalog…",
                loading = true,
                modifier = Modifier.weight(1f),
            )
            state.catalogChannelCount == 0 -> MobileLiveEmptyState(
                failed = channelRefreshFailed,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            state.channels.isEmpty() -> MobileLiveStatusState(
                title = "No matching channels",
                detail = if (state.query.searchTerm.isNotBlank()) {
                    "Try another search term or category."
                } else {
                    "This category does not contain any channels."
                },
                modifier = Modifier.weight(1f),
            )
            else -> LazyColumn(
                state = channelListState,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(state.categories, state.query.categoryKey) {
                        var totalHorizontalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalHorizontalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalHorizontalDrag += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                val triggerDistance = max(
                                    viewConfiguration.touchSlop * 4f,
                                    size.width * MOBILE_CATEGORY_SWIPE_TRIGGER_FRACTION,
                                )
                                if (abs(totalHorizontalDrag) >= triggerDistance) {
                                    selectAdjacentCategory(totalHorizontalDrag)
                                }
                            },
                        )
                    },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                items(
                    items = state.channels,
                    key = { it.channelId },
                ) { channel ->
                    MobileChannelRow(
                        channel = channel,
                        currentProgram = currentEpgByChannelId[channel.channelId],
                        active = channel.channelId == playingChannelId,
                        onClick = { onChannelSelected(channel.channelId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileLiveSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = "Search channels",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = if (value.isNotBlank()) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
            },
        )
    }
}

@Composable
private fun MobileSearchAction(
    searchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
) {
    IconButton(
        onClick = {
            val next = !searchExpanded
            onSearchExpandedChange(next)
            if (!next) onSearchChange("")
        },
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
            contentDescription = if (searchExpanded) "Close search" else "Search channels",
            tint = if (searchExpanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun MobileCategoryTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )
    }
}

@Composable
private fun MobileChannelRow(
    channel: LiveChannelItem,
    currentProgram: EpgProgram?,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                selected = active
                stateDescription = when {
                    active && channel.isFavorite -> "Currently previewing, favorite channel"
                    active -> "Currently previewing"
                    channel.isFavorite -> "Favorite channel"
                    else -> "Channel"
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = if (active) "Open full view" else "Open preview",
                onClick = onClick,
            )
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    Color.Transparent
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(44.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
            )
            MobileChannelLogo(
                logoRef = channel.logoRef,
                title = channel.displayName,
                modifier = Modifier.size(44.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                currentProgram?.let { program ->
                    Text(
                        text = buildString {
                            program.startLabel?.takeIf(String::isNotBlank)?.let { start ->
                                append(start)
                                append(" · ")
                            }
                            append(program.title)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (channel.isFavorite) {
                Text(
                    text = "★",
                    modifier = Modifier.clearAndSetSemantics { },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 57.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
        )
    }
}

@Composable
private fun MobileChannelLogo(
    logoRef: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resolver = remember(context) { LiveChannelLogoResolver(context.applicationContext) }
    val initialState = remember(logoRef) {
        if (logoRef.isNullOrBlank()) {
            MobileChannelLogoState.Unavailable
        } else {
            MobileChannelLogoState.Loading
        }
    }
    val state by produceState<MobileChannelLogoState>(
        initialValue = initialState,
        key1 = logoRef,
    ) {
        val resolvedUrl = resolver.resolve(logoRef)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (resolvedUrl == null) {
            value = MobileChannelLogoState.Unavailable
            return@produceState
        }

        cachedMobileChannelLogo(resolvedUrl)?.let { cached ->
            value = MobileChannelLogoState.Loaded(cached)
            return@produceState
        }

        value = MobileChannelLogoState.Loading
        val loaded = mobileChannelLogoRequestCoordinator.coalesce(resolvedUrl) {
            loadMobileChannelLogo(resolvedUrl)
        }
        value = if (loaded != null) {
            cacheMobileChannelLogo(resolvedUrl, loaded)
            MobileChannelLogoState.Loaded(loaded)
        } else {
            MobileChannelLogoState.Unavailable
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        when (val currentState = state) {
            is MobileChannelLogoState.Loaded -> Image(
                bitmap = currentState.image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit,
            )
            MobileChannelLogoState.Loading -> Unit
            MobileChannelLogoState.Unavailable -> Text(
                text = mobileChannelLogoFallbackLabel(title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun cachedMobileChannelLogo(url: String): ImageBitmap? = synchronized(mobileChannelLogoCacheLock) {
    mobileChannelLogoMemoryCache[url]
}

private fun cacheMobileChannelLogo(url: String, image: ImageBitmap) {
    synchronized(mobileChannelLogoCacheLock) {
        mobileChannelLogoMemoryCache[url] = image
    }
}

internal fun mobileChannelLogoFallbackLabel(title: String): String =
    title.trim().firstOrNull()?.uppercase() ?: "•"

private suspend fun loadMobileChannelLogo(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        SourceHttpClient.shared.newCall(
            Request.Builder().url(url).get().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            if (body.contentLength() > MOBILE_LOGO_MAX_BYTES.toLong()) return@use null
            val bytes = readMobileLogoBytes(body.byteStream()) ?: return@use null
            decodeMobileLogo(bytes)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun readMobileLogoBytes(input: java.io.InputStream): ByteArray? {
    val output = ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > MOBILE_LOGO_MAX_BYTES) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeMobileLogo(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sampleSize = calculateMobileLogoInSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
    )
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )?.asImageBitmap()
}

internal fun calculateMobileLogoInSampleSize(
    width: Int,
    height: Int,
    maxEdgePx: Int = MOBILE_LOGO_MAX_EDGE_PX,
): Int {
    require(maxEdgePx > 0) { "maxEdgePx must be positive" }
    if (width <= 0 || height <= 0) return 1

    var sampleSize = 1
    while (
        width / sampleSize > maxEdgePx ||
        height / sampleSize > maxEdgePx
    ) {
        if (sampleSize > Int.MAX_VALUE / 2) break
        sampleSize *= 2
    }
    return sampleSize
}

@Composable
private fun MobileLiveStatusState(
    title: String,
    detail: String,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MobileLiveEmptyState(
    failed: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (failed) "Channels could not be refreshed" else "No Live channels",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Check the playlist or refresh it from Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VNextMediaPrimaryAction(
            label = "Retry",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
        VNextMediaSecondaryAction(
            label = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
