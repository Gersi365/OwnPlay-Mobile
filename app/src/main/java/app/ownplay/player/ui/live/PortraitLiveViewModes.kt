package app.ownplay.player.ui.live

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.live.LiveBrowseOrder
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.live.LiveCategory
import app.ownplay.player.live.LiveChannelItem
import app.ownplay.player.live.LiveChannelLogoResolver
import app.ownplay.player.source.network.SourceHttpClient
import app.ownplay.player.ui.tv.TvPopupFocusAction
import app.ownplay.player.ui.tv.TvPopupFocusPolicy
import app.ownplay.player.ui.view.ContentViewMode
import app.ownplay.player.ui.view.ContentViewModeMenu
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

private const val MAX_CHANNEL_LOGO_BYTES = 2 * 1024 * 1024
private const val MAX_CHANNEL_LOGO_EDGE_PX = 256

@Composable
internal fun PortraitLiveBrowseWithViewModes(
    state: LiveBrowseState,
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
    focusChannelId: String? = null,
    focusRequestGeneration: Int = 0,
    channelFocusRequester: FocusRequester? = null,
    showCategoryStrip: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val searchTriggerFocusRequester = remember { FocusRequester() }
    val searchFieldFocusRequester = remember { FocusRequester() }
    var searchExpanded by remember { mutableStateOf(false) }
    val showSearch = searchExpanded || state.query.searchTerm.isNotBlank()

    LaunchedEffect(showCategoryStrip, state.categories, state.query.categoryKey) {
        if (!showCategoryStrip || state.categories.isEmpty()) return@LaunchedEffect
        val selected = state.query.categoryKey
        if (selected == null || state.categories.none { it.providerCategoryKey == selected }) {
            onCategorySelected(state.categories.first().providerCategoryKey)
        }
    }

    LaunchedEffect(searchExpanded, isTelevision) {
        if (!isTelevision || !searchExpanded) return@LaunchedEffect
        withFrameNanos { }
        searchFieldFocusRequester.requestFocus()
    }

    LaunchedEffect(playingChannelId, isTelevision) {
        if (isTelevision && playingChannelId != null) {
            searchExpanded = false
        }
    }

    BackHandler(
        enabled = isTelevision && searchExpanded && playingChannelId == null,
    ) {
        onSearchChange("")
        searchExpanded = false
        searchTriggerFocusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        LiveViewModeToolbar(
            state = state,
            viewMode = viewMode,
            showSearch = showSearch,
            tvFocusManagement = isTelevision,
            searchTriggerFocusRequester = searchTriggerFocusRequester,
            channelFocusRequester = channelFocusRequester,
            onToggleSearch = {
                if (showSearch) {
                    onSearchChange("")
                    searchExpanded = false
                } else {
                    searchExpanded = true
                }
            },
            onViewModeSelected = onViewModeSelected,
            onFavoritesOnlyChanged = onFavoritesOnlyChanged,
            onOrderChanged = onOrderChanged,
            onCustomGroupSelected = onCustomGroupSelected,
        )

        AnimatedVisibility(
            visible = showSearch,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            OutlinedTextField(
                value = state.query.searchTerm,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .then(
                        if (isTelevision) {
                            Modifier
                                .focusRequester(searchFieldFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    when {
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp -> {
                                            searchTriggerFocusRequester.requestFocus()
                                            true
                                        }
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionDown &&
                                            state.channels.isNotEmpty() &&
                                            channelFocusRequester != null -> {
                                            channelFocusRequester.requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
                singleLine = true,
                placeholder = { Text("Search channels") },
                shape = RoundedCornerShape(14.dp),
            )
        }

        if (showCategoryStrip) {
            LiveViewCategoryStrip(
                categories = state.categories,
                selectedCategoryKey = state.query.categoryKey,
                onCategorySelected = onCategorySelected,
            )
        }

        LiveChannelView(
            channels = state.channels,
            playingChannelId = playingChannelId,
            currentEpgByChannelId = currentEpgByChannelId,
            viewMode = viewMode,
            onChannelSelected = onChannelSelected,
            focusChannelId = focusChannelId,
            focusRequestGeneration = focusRequestGeneration,
            channelFocusRequester = channelFocusRequester,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LiveViewModeToolbar(
    state: LiveBrowseState,
    viewMode: ContentViewMode,
    showSearch: Boolean,
    tvFocusManagement: Boolean,
    searchTriggerFocusRequester: FocusRequester,
    channelFocusRequester: FocusRequester?,
    onToggleSearch: () -> Unit,
    onViewModeSelected: (ContentViewMode) -> Unit,
    onFavoritesOnlyChanged: (Boolean) -> Unit,
    onOrderChanged: (LiveBrowseOrder) -> Unit,
    onCustomGroupSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (
                    tvFocusManagement &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown &&
                    state.channels.isNotEmpty() &&
                    channelFocusRequester != null
                ) {
                    channelFocusRequester.requestFocus()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${state.channels.size} channels",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { onFavoritesOnlyChanged(!state.query.favoritesOnly) },
        ) {
            Icon(
                imageVector = if (state.query.favoritesOnly) {
                    Icons.Filled.Favorite
                } else {
                    Icons.Filled.FavoriteBorder
                },
                contentDescription = if (state.query.favoritesOnly) {
                    "Show all channels"
                } else {
                    "Favorites only"
                },
                tint = if (state.query.favoritesOnly) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        LiveBrowseOptionsMenu(
            state = state,
            tvFocusManagement = tvFocusManagement,
            onOrderChanged = onOrderChanged,
            onCustomGroupSelected = onCustomGroupSelected,
        )
        ContentViewModeMenu(
            mode = viewMode,
            onModeSelected = onViewModeSelected,
            tvFocusManagement = tvFocusManagement,
        )
        IconButton(
            onClick = onToggleSearch,
            modifier = if (tvFocusManagement) {
                Modifier.focusRequester(searchTriggerFocusRequester)
            } else {
                Modifier
            },
        ) {
            Icon(
                imageVector = if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                contentDescription = if (showSearch) "Close search" else "Search channels",
            )
        }
    }
}

@Composable
private fun LiveBrowseOptionsMenu(
    state: LiveBrowseState,
    tvFocusManagement: Boolean,
    onOrderChanged: (LiveBrowseOrder) -> Unit,
    onCustomGroupSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var wasExpanded by remember { mutableStateOf(false) }
    val triggerFocusRequester = remember { FocusRequester() }
    val selectedOrderFocusRequester = remember(state.query.order) { FocusRequester() }

    BackHandler(enabled = tvFocusManagement && expanded) {
        expanded = false
    }

    LaunchedEffect(expanded, tvFocusManagement, state.query.order) {
        val focusAction = TvPopupFocusPolicy.action(
            enabled = tvFocusManagement,
            expanded = expanded,
            wasExpanded = wasExpanded,
        )
        wasExpanded = expanded
        when (focusAction) {
            TvPopupFocusAction.FOCUS_SELECTED_ITEM -> {
                withFrameNanos { }
                selectedOrderFocusRequester.requestFocus()
            }
            TvPopupFocusAction.RESTORE_TRIGGER -> {
                withFrameNanos { }
                triggerFocusRequester.requestFocus()
            }
            TvPopupFocusAction.NONE -> Unit
        }
    }

    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = if (tvFocusManagement) {
                Modifier.focusRequester(triggerFocusRequester)
            } else {
                Modifier
            },
        ) {
            Text("Browse")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LiveBrowseOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                append(order.liveViewLabel())
                                if (order == state.query.order) append(" ✓")
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onOrderChanged(order)
                    },
                    modifier = if (tvFocusManagement && order == state.query.order) {
                        Modifier.focusRequester(selectedOrderFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
            if (state.customGroups.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            if (state.query.customGroupId == null) "No group filter ✓" else "No group filter",
                        )
                    },
                    onClick = {
                        expanded = false
                        onCustomGroupSelected(null)
                    },
                )
                state.customGroups.forEach { group ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append(group.name)
                                    if (state.query.customGroupId == group.groupId) append(" ✓")
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onCustomGroupSelected(group.groupId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveViewCategoryStrip(
    categories: List<LiveCategory>,
    selectedCategoryKey: String?,
    onCategorySelected: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(
            items = categories,
            key = LiveCategory::providerCategoryKey,
        ) { category ->
            FilterChip(
                selected = selectedCategoryKey == category.providerCategoryKey,
                onClick = { onCategorySelected(category.providerCategoryKey) },
                label = {
                    Text(
                        text = category.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun LiveChannelView(
    channels: List<LiveChannelItem>,
    playingChannelId: String?,
    currentEpgByChannelId: Map<String, EpgProgram>,
    viewMode: ContentViewMode,
    onChannelSelected: (String) -> Unit,
    focusChannelId: String?,
    focusRequestGeneration: Int,
    channelFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No matching channels",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val cardMinSize = if (isTelevision) 176.dp else 156.dp
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var requesterChannelId by remember { mutableStateOf(focusChannelId) }
    val focusIndex = remember(channels, focusChannelId) {
        channels.indexOfFirst { channel -> channel.channelId == focusChannelId }
    }

    LaunchedEffect(
        viewMode,
        focusChannelId,
        focusRequestGeneration,
        focusIndex,
        channelFocusRequester,
    ) {
        val requester = channelFocusRequester ?: return@LaunchedEffect
        if (focusRequestGeneration <= 0 || focusIndex < 0) return@LaunchedEffect
        requesterChannelId = focusChannelId
        when (viewMode) {
            ContentViewMode.LIST,
            ContentViewMode.COMPACT,
            -> listState.scrollToItem(focusIndex)

            ContentViewMode.CARDS -> gridState.scrollToItem(focusIndex)
        }
        withFrameNanos { }
        requester.requestFocus()
    }

    when (viewMode) {
        ContentViewMode.LIST -> LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = LiveChannelItem::channelId) { channel ->
                val channelModifier = if (
                    channel.channelId == requesterChannelId && channelFocusRequester != null
                ) {
                    Modifier.focusRequester(channelFocusRequester)
                } else {
                    Modifier
                }
                LiveChannelListRow(
                    channel = channel,
                    active = channel.channelId == playingChannelId,
                    currentProgram = currentEpgByChannelId[channel.channelId],
                    onClick = { onChannelSelected(channel.channelId) },
                    onFocused = { requesterChannelId = channel.channelId },
                    modifier = channelModifier,
                )
            }
        }

        ContentViewMode.COMPACT -> LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(channels, key = LiveChannelItem::channelId) { channel ->
                val channelModifier = if (
                    channel.channelId == requesterChannelId && channelFocusRequester != null
                ) {
                    Modifier.focusRequester(channelFocusRequester)
                } else {
                    Modifier
                }
                LiveChannelCompactRow(
                    channel = channel,
                    active = channel.channelId == playingChannelId,
                    currentProgram = currentEpgByChannelId[channel.channelId],
                    onClick = { onChannelSelected(channel.channelId) },
                    onFocused = { requesterChannelId = channel.channelId },
                    modifier = channelModifier,
                )
            }
        }

        ContentViewMode.CARDS -> LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = cardMinSize),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            gridItems(channels, key = LiveChannelItem::channelId) { channel ->
                val channelModifier = if (
                    channel.channelId == requesterChannelId && channelFocusRequester != null
                ) {
                    Modifier.focusRequester(channelFocusRequester)
                } else {
                    Modifier
                }
                LiveChannelCard(
                    channel = channel,
                    active = channel.channelId == playingChannelId,
                    currentProgram = currentEpgByChannelId[channel.channelId],
                    onClick = { onChannelSelected(channel.channelId) },
                    onFocused = { requesterChannelId = channel.channelId },
                    modifier = channelModifier,
                )
            }
        }
    }
}

@Composable
private fun LiveChannelCompactRow(
    channel: LiveChannelItem,
    active: Boolean,
    currentProgram: EpgProgram?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(channel.channelId) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onFocused()
            }
            .clip(RoundedCornerShape(10.dp))
            .background(liveSelectionBackground(active || focused))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        RemoteChannelLogo(
            url = channel.logoRef,
            title = channel.displayName,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (active || focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            liveSecondaryLabel(channel, currentProgram)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active || focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LiveChannelTrailingState(channel = channel)
    }
}

@Composable
private fun LiveChannelListRow(
    channel: LiveChannelItem,
    active: Boolean,
    currentProgram: EpgProgram?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(channel.channelId) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onFocused()
            }
            .clip(RoundedCornerShape(12.dp))
            .background(liveSelectionBackground(active || focused))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteChannelLogo(
            url = channel.logoRef,
            title = channel.displayName,
            modifier = Modifier.size(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (active || focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            liveSecondaryLabel(channel, currentProgram)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active || focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LiveChannelTrailingState(channel = channel)
    }
}

@Composable
private fun LiveChannelCard(
    channel: LiveChannelItem,
    active: Boolean,
    currentProgram: EpgProgram?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(channel.channelId) { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onFocused()
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (active || focused) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                RemoteChannelLogo(
                    url = channel.logoRef,
                    title = channel.displayName,
                    modifier = Modifier.size(56.dp),
                )
            }
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (active || focused) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            liveSecondaryLabel(channel, currentProgram)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active || focused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = channel.categoryName.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LiveChannelTrailingState(channel = channel)
            }
        }
    }
}

@Composable
private fun LiveChannelTrailingState(channel: LiveChannelItem) {
    if (channel.isFavorite) {
        Text(
            text = "★",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RemoteChannelLogo(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resolver = remember(context) {
        LiveChannelLogoResolver(context.applicationContext)
    }
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = resolver.resolve(url)
            ?.takeIf(String::isNotBlank)
            ?.let { logoUrl -> loadChannelLogo(logoUrl) }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { logo ->
            Image(
                bitmap = logo,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(
            text = title.trim().firstOrNull()?.uppercase() ?: "•",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun loadChannelLogo(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        SourceHttpClient.shared.newCall(
            Request.Builder().url(url).get().build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_CHANNEL_LOGO_BYTES.toLong()) return@use null
            val bytes = readChannelLogoBytes(body.byteStream()) ?: return@use null
            decodeChannelLogo(bytes)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun readChannelLogoBytes(input: java.io.InputStream): ByteArray? {
    val output = ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > MAX_CHANNEL_LOGO_BYTES) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeChannelLogo(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / sampleSize > MAX_CHANNEL_LOGO_EDGE_PX ||
        bounds.outHeight / sampleSize > MAX_CHANNEL_LOGO_EDGE_PX
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun liveSelectionBackground(active: Boolean) = if (active) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.50f)
} else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
}

private fun liveSecondaryLabel(
    channel: LiveChannelItem,
    currentProgram: EpgProgram?,
): String? = currentProgram?.let { program ->
    buildString {
        append("Now")
        program.startLabel?.let { start ->
            append(" · ")
            append(start)
        }
        append(" · ")
        append(program.title)
    }
} ?: channel.categoryName?.takeIf(String::isNotBlank)

private fun LiveBrowseOrder.liveViewLabel(): String = when (this) {
    LiveBrowseOrder.PROVIDER -> "Provider order"
    LiveBrowseOrder.MY_ORDER -> "My order"
    LiveBrowseOrder.FAVORITE_ORDER -> "Favorite order"
    LiveBrowseOrder.RECENTLY_WATCHED -> "Recently watched"
    LiveBrowseOrder.A_TO_Z -> "A–Z"
    LiveBrowseOrder.Z_TO_A -> "Z–A"
    LiveBrowseOrder.CATEGORY -> "Category"
}
