package app.ownplay.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.live.LiveBrowseOrder
import app.ownplay.player.live.LiveBrowseSession
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.personalization.CategoryOrderMutationResult
import app.ownplay.player.personalization.CategoryVisibilityMutationResult
import app.ownplay.player.personalization.ChannelBulkAction
import app.ownplay.player.personalization.ChannelEditReducer
import app.ownplay.player.personalization.ChannelEditState
import app.ownplay.player.personalization.FavoriteMutationResult
import app.ownplay.player.personalization.ManualOrderMutationResult
import app.ownplay.player.personalization.ManualOrderPlacement
import app.ownplay.player.ui.live.LiveBrowseScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LiveManagementScreen(
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    onBack: () -> Unit,
    focusBackOnEntry: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val backFocusRequester = remember { FocusRequester() }
    var sourceId by remember(summaries) {
        mutableStateOf(summaries.firstOrNull()?.sourceId)
    }
    val selectedSourceId = sourceId

    LaunchedEffect(isTelevision, focusBackOnEntry, selectedSourceId) {
        if (isTelevision && focusBackOnEntry) {
            backFocusRequester.requestFocus()
        }
    }

    if (selectedSourceId == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Live management", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add a playlist before managing categories and channels.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocusRequester),
            ) { Text("Back") }
        }
        return
    }

    val browseSession = remember(selectedSourceId) { LiveBrowseSession() }
    val browseFlow = remember(selectedSourceId) {
        browseSession.observe(runtime.observeLiveCatalog(selectedSourceId))
    }
    val state by browseFlow.collectAsState(initial = LiveBrowseState())
    val scope = rememberCoroutineScope()
    var editState by remember(selectedSourceId) {
        mutableStateOf(ChannelEditState(isEditing = true))
    }
    var showCategoryReorder by remember(selectedSourceId) { mutableStateOf(false) }
    var categoryMutationInFlight by remember(selectedSourceId) { mutableStateOf(false) }
    var categoryError by remember(selectedSourceId) { mutableStateOf<String?>(null) }
    var orderError by remember(selectedSourceId) { mutableStateOf<String?>(null) }

    val selectedCategory = state.query.categoryKey?.let { key ->
        state.categories.firstOrNull { category -> category.providerCategoryKey == key }
    }
    val selectedChannelId = editState.selectedChannelIds.singleOrNull()
    val selectedChannelIndex = selectedChannelId?.let { channelId ->
        state.channels.indexOfFirst { channel -> channel.channelId == channelId }
    } ?: -1
    val canMoveSelectedUp = selectedChannelIndex > 0
    val canMoveSelectedDown = selectedChannelIndex >= 0 && selectedChannelIndex < state.channels.lastIndex

    LaunchedEffect(selectedSourceId) {
        browseSession.setIncludeHidden(true)
        browseSession.setOrder(LiveBrowseOrder.MY_ORDER)
    }

    LaunchedEffect(state.channels) {
        editState = ChannelEditReducer.retainAvailable(
            state = editState,
            availableChannelIds = state.channels.map { channel -> channel.channelId },
        )
    }

    fun executeBulkAction(action: ChannelBulkAction) {
        val selection = editState.selectedChannelIds
        if (selection.isEmpty()) return
        scope.launch {
            runtime.executeChannelBulkAction(
                sourceId = selectedSourceId,
                selectedChannelIds = selection,
                action = action,
            )
        }
    }

    fun moveSelectedRelative(anchorChannelId: String, placement: ManualOrderPlacement) {
        val channelId = selectedChannelId ?: return
        val useFavoriteOrder =
            state.query.favoritesOnly && state.query.order == LiveBrowseOrder.FAVORITE_ORDER
        val useManualOrder = state.query.order == LiveBrowseOrder.MY_ORDER
        if (!useFavoriteOrder && !useManualOrder) return

        orderError = null
        scope.launch {
            try {
                if (useFavoriteOrder) {
                    when (
                        runtime.moveFavoriteRelative(
                            sourceId = selectedSourceId,
                            channelId = channelId,
                            anchorChannelId = anchorChannelId,
                            placement = placement,
                        )
                    ) {
                        is FavoriteMutationResult.Success -> orderError = null
                        is FavoriteMutationResult.Failure -> {
                            orderError = "Could not save channel order."
                        }
                    }
                } else {
                    when (
                        runtime.moveChannelRelative(
                            sourceId = selectedSourceId,
                            channelId = channelId,
                            anchorChannelId = anchorChannelId,
                            placement = placement,
                        )
                    ) {
                        is ManualOrderMutationResult.Success -> orderError = null
                        is ManualOrderMutationResult.Rejected,
                        ManualOrderMutationResult.InvalidSourceId,
                        ManualOrderMutationResult.PersistenceFailure,
                        -> orderError = "Could not save channel order."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                orderError = "Could not save channel order."
            }
        }
    }

    fun persistDraggedChannelMove(
        channelId: String,
        anchorChannelId: String,
        placement: ManualOrderPlacement,
        favoriteOrder: Boolean,
    ) {
        orderError = null
        scope.launch {
            try {
                if (favoriteOrder) {
                    when (
                        runtime.moveFavoriteRelative(
                            sourceId = selectedSourceId,
                            channelId = channelId,
                            anchorChannelId = anchorChannelId,
                            placement = placement,
                        )
                    ) {
                        is FavoriteMutationResult.Success -> orderError = null
                        is FavoriteMutationResult.Failure -> {
                            orderError = "Could not save channel order."
                        }
                    }
                } else {
                    when (
                        runtime.moveChannelRelative(
                            sourceId = selectedSourceId,
                            channelId = channelId,
                            anchorChannelId = anchorChannelId,
                            placement = placement,
                        )
                    ) {
                        is ManualOrderMutationResult.Success -> orderError = null
                        is ManualOrderMutationResult.Rejected,
                        ManualOrderMutationResult.InvalidSourceId,
                        ManualOrderMutationResult.PersistenceFailure,
                        -> orderError = "Could not save channel order."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                orderError = "Could not save channel order."
            }
        }
    }

    fun moveSelectedUp() {
        if (!canMoveSelectedUp) return
        moveSelectedRelative(
            anchorChannelId = state.channels[selectedChannelIndex - 1].channelId,
            placement = ManualOrderPlacement.BEFORE,
        )
    }

    fun moveSelectedDown() {
        if (!canMoveSelectedDown) return
        moveSelectedRelative(
            anchorChannelId = state.channels[selectedChannelIndex + 1].channelId,
            placement = ManualOrderPlacement.AFTER,
        )
    }

    fun moveSelectedToTop() {
        if (!canMoveSelectedUp) return
        moveSelectedRelative(
            anchorChannelId = state.channels.first().channelId,
            placement = ManualOrderPlacement.BEFORE,
        )
    }

    fun moveSelectedToBottom() {
        if (!canMoveSelectedDown) return
        moveSelectedRelative(
            anchorChannelId = state.channels.last().channelId,
            placement = ManualOrderPlacement.AFTER,
        )
    }

    fun toggleCategoryVisibility() {
        val category = selectedCategory ?: return
        if (categoryMutationInFlight) return
        categoryMutationInFlight = true
        categoryError = null
        scope.launch {
            try {
                val result = if (category.isHidden) {
                    runtime.unhideCategory(selectedSourceId, category.providerCategoryKey)
                } else {
                    runtime.hideCategory(selectedSourceId, category.providerCategoryKey)
                }
                if (result is CategoryVisibilityMutationResult.Failure) {
                    categoryError = "Could not save category visibility."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                categoryError = "Could not save category visibility."
            } finally {
                categoryMutationInFlight = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Live management",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Reorder, hide, rename and organize channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ManagementSourceMenu(
                summaries = summaries,
                selectedSourceId = selectedSourceId,
                onSelected = { nextSourceId ->
                    sourceId = nextSourceId
                },
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocusRequester),
            ) { Text("Done") }
        }

        if (selectedCategory != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedCategory.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = ::toggleCategoryVisibility,
                    enabled = !categoryMutationInFlight,
                ) {
                    Text(if (selectedCategory.isHidden) "Unhide category" else "Hide category")
                }
            }
        }

        categoryError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        orderError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 14.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (isTelevision && selectedChannelId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Remote order",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = ::moveSelectedToTop) { Text("Top") }
                TextButton(onClick = ::moveSelectedUp) { Text("Move up") }
                TextButton(onClick = ::moveSelectedDown) { Text("Move down") }
                TextButton(onClick = ::moveSelectedToBottom) { Text("Bottom") }
            }
        }

        LiveBrowseScreen(
            state = state,
            onSearchChange = browseSession::updateSearch,
            onCategorySelected = {
                categoryError = null
                browseSession.selectCategory(it)
            },
            onFavoritesOnlyChanged = { enabled ->
                browseSession.setFavoritesOnly(enabled)
                browseSession.setOrder(
                    if (enabled) LiveBrowseOrder.FAVORITE_ORDER else LiveBrowseOrder.MY_ORDER,
                )
            },
            onOrderChanged = browseSession::setOrder,
            onCustomGroupSelected = browseSession::selectCustomGroup,
            onHiddenOnlyChanged = { enabled ->
                browseSession.setHiddenOnly(
                    enabled = enabled,
                    includeHiddenWhenDisabled = true,
                )
            },
            editState = editState,
            onEditModeChanged = { editing ->
                if (!editing) onBack()
            },
            onReorderCategoriesRequested = {
                orderError = null
                showCategoryReorder = true
            },
            onChannelSelectionToggle = { channelId ->
                editState = ChannelEditReducer.toggleSelection(editState, channelId)
            },
            onSelectVisible = {
                editState = ChannelEditReducer.selectVisible(
                    state = editState,
                    visibleChannelIds = state.channels.map { channel -> channel.channelId },
                )
            },
            onClearSelection = {
                editState = ChannelEditReducer.clearSelection(editState)
            },
            onBulkAction = ::executeBulkAction,
            onCreateGroup = { name -> scope.launch { runtime.createCustomGroup(name) } },
            onRenameGroup = { groupId, name ->
                scope.launch { runtime.renameCustomGroup(groupId, name) }
            },
            onDeleteGroup = { groupId -> scope.launch { runtime.deleteCustomGroup(groupId) } },
            onSetLocalDisplayName = { channelId, name ->
                scope.launch { runtime.setLocalDisplayName(selectedSourceId, channelId, name) }
            },
            onClearLocalDisplayName = { channelId ->
                scope.launch { runtime.clearLocalDisplayName(selectedSourceId, channelId) }
            },
            onSetLogoOverride = { channelId, logoValue ->
                scope.launch { runtime.setLogoOverride(selectedSourceId, channelId, logoValue) }
            },
            onClearLogoOverride = { channelId ->
                scope.launch { runtime.clearLogoOverride(selectedSourceId, channelId) }
            },
            onManualMoveRelative = { channelId, anchorChannelId, placement ->
                persistDraggedChannelMove(
                    channelId = channelId,
                    anchorChannelId = anchorChannelId,
                    placement = placement,
                    favoriteOrder = false,
                )
            },
            onFavoriteMoveRelative = { channelId, anchorChannelId, placement ->
                persistDraggedChannelMove(
                    channelId = channelId,
                    anchorChannelId = anchorChannelId,
                    placement = placement,
                    favoriteOrder = true,
                )
            },
            onChannelSelected = {},
            modifier = Modifier.weight(1f),
        )
    }

    if (showCategoryReorder) {
        CategoryReorderSheet(
            categories = state.categories,
            onOrderChanged = { orderedKeys ->
                orderError = null
                scope.launch {
                    try {
                        when (
                            runtime.setCategoryOrder(
                                sourceId = selectedSourceId,
                                orderedCategoryKeys = orderedKeys,
                            )
                        ) {
                            is CategoryOrderMutationResult.Success -> orderError = null
                            is CategoryOrderMutationResult.Failure -> {
                                orderError = "Could not save category order."
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        orderError = "Could not save category order."
                    }
                }
            },
            onDismiss = { showCategoryReorder = false },
        )
    }
}

@Composable
private fun ManagementSourceMenu(
    summaries: List<PlaylistSourceSummary>,
    selectedSourceId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = summaries.firstOrNull { it.sourceId == selectedSourceId }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = selected?.name ?: "Source",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            summaries.forEach { summary ->
                DropdownMenuItem(
                    text = { Text(summary.name) },
                    onClick = {
                        expanded = false
                        onSelected(summary.sourceId)
                    },
                )
            }
        }
    }
}
