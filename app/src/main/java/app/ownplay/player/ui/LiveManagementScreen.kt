package app.ownplay.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.OwnPlayAppRuntime
import app.ownplay.player.live.LiveBrowseOrder
import app.ownplay.player.live.LiveBrowseSession
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.live.LiveCategory
import app.ownplay.player.live.LiveChannelItem
import app.ownplay.player.live.LiveCustomGroup
import app.ownplay.player.persistence.PlaylistSourceSummary
import app.ownplay.player.personalization.CategoryOrderMutationResult
import app.ownplay.player.personalization.CategoryVisibilityMutationResult
import app.ownplay.player.personalization.ChannelBulkAction
import app.ownplay.player.personalization.ChannelEditReducer
import app.ownplay.player.personalization.ChannelEditState
import app.ownplay.player.personalization.FavoriteMutationResult
import app.ownplay.player.personalization.ManualOrderMutationResult
import app.ownplay.player.personalization.ManualOrderPlacement
import app.ownplay.player.ui.live.ChannelCustomizationDialog
import app.ownplay.player.ui.live.CustomGroupManagerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LiveManagementScreen(
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    onBack: () -> Unit,
    focusBackOnEntry: Boolean = false,
) {
    val backFocusRequester = remember { FocusRequester() }
    var sourceId by remember(summaries) {
        mutableStateOf(summaries.firstOrNull()?.sourceId)
    }
    val selectedSourceId = sourceId

    LaunchedEffect(focusBackOnEntry, selectedSourceId) {
        if (focusBackOnEntry) {
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
    var hiddenCategoriesExpanded by remember(selectedSourceId) { mutableStateOf(false) }
    var hiddenChannelsExpanded by remember(selectedSourceId) { mutableStateOf(false) }
    var showCategoryReorder by remember(selectedSourceId) { mutableStateOf(false) }
    var showGroupManager by remember(selectedSourceId) { mutableStateOf(false) }
    var customizeTarget by remember(selectedSourceId) { mutableStateOf<LiveChannelItem?>(null) }
    var categoryMutationInFlight by remember(selectedSourceId) { mutableStateOf(false) }
    var categoryError by remember(selectedSourceId) { mutableStateOf<String?>(null) }
    var orderError by remember(selectedSourceId) { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedSourceId) {
        browseSession.setIncludeHidden(true)
        browseSession.setOrder(LiveBrowseOrder.MY_ORDER)
    }

    LaunchedEffect(
        state.categories,
        state.customGroups,
        state.query.categoryKey,
        state.query.customGroupId,
    ) {
        val selectedCategoryStillExists = state.query.categoryKey?.let { key ->
            state.categories.any { category -> category.providerCategoryKey == key }
        } ?: false
        val selectedGroupStillExists = state.query.customGroupId?.let { groupId ->
            state.customGroups.any { group -> group.groupId == groupId }
        } ?: false

        when {
            state.query.categoryKey != null && !selectedCategoryStillExists -> {
                browseSession.selectCategory(null)
            }
            state.query.customGroupId != null && !selectedGroupStillExists -> {
                browseSession.selectCustomGroup(null)
            }
            state.query.categoryKey == null && state.query.customGroupId == null -> {
                state.categories.firstOrNull { category -> !category.isHidden }
                    ?.providerCategoryKey
                    ?.let(browseSession::selectCategory)
                    ?: state.categories.firstOrNull()
                        ?.providerCategoryKey
                        ?.let(browseSession::selectCategory)
            }
        }
    }

    LaunchedEffect(state.channels) {
        editState = ChannelEditReducer.retainAvailable(
            state = editState,
            availableChannelIds = state.channels.map { channel -> channel.channelId },
        )
    }

    val visibleCategories = state.categories.filterNot(LiveCategory::isHidden)
    val hiddenCategories = state.categories.filter(LiveCategory::isHidden)
    val selectedCategory = state.query.categoryKey?.let { key ->
        state.categories.firstOrNull { category -> category.providerCategoryKey == key }
    }
    val selectedGroup = state.query.customGroupId?.let { groupId ->
        state.customGroups.firstOrNull { group -> group.groupId == groupId }
    }
    val visibleChannels = state.channels.filterNot(LiveChannelItem::isHidden)
    val hiddenChannels = state.channels.filter(LiveChannelItem::isHidden)
    val selectedChannelId = editState.selectedChannelIds.singleOrNull()
    val selectedChannel = selectedChannelId?.let { channelId ->
        state.channels.firstOrNull { channel -> channel.channelId == channelId }
    }
    val selectedChannelIndex = selectedChannelId?.let { channelId ->
        state.channels.indexOfFirst { channel -> channel.channelId == channelId }
    } ?: -1
    val canMoveSelectedUp = selectedChannelIndex > 0
    val canMoveSelectedDown = selectedChannelIndex >= 0 && selectedChannelIndex < state.channels.lastIndex

    fun clearSelection() {
        editState = ChannelEditReducer.clearSelection(editState)
    }

    fun selectCategory(categoryKey: String) {
        categoryError = null
        orderError = null
        clearSelection()
        browseSession.selectCustomGroup(null)
        browseSession.selectCategory(categoryKey)
    }

    fun selectGroup(groupId: String) {
        categoryError = null
        orderError = null
        clearSelection()
        browseSession.selectCategory(null)
        browseSession.selectCustomGroup(groupId)
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
        orderError = null
        scope.launch {
            try {
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

    fun setCategoryHidden(category: LiveCategory, hidden: Boolean) {
        if (categoryMutationInFlight || category.isHidden == hidden) return
        categoryMutationInFlight = true
        categoryError = null
        scope.launch {
            try {
                val result = if (hidden) {
                    runtime.hideCategory(selectedSourceId, category.providerCategoryKey)
                } else {
                    runtime.unhideCategory(selectedSourceId, category.providerCategoryKey)
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
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
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
                    text = "Organize categories, hidden content, channel order and custom groups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ManagementSourceMenu(
                summaries = summaries,
                selectedSourceId = selectedSourceId,
                onSelected = { nextSourceId -> sourceId = nextSourceId },
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.focusRequester(backFocusRequester),
            ) { Text("Done") }
        }

        categoryError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        orderError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            item(key = "visible-categories-title") {
                ManagementSectionHeader(
                    title = "Visible Categories",
                    count = visibleCategories.size,
                    actionLabel = "Reorder",
                    onAction = { showCategoryReorder = true },
                )
            }
            if (visibleCategories.isEmpty()) {
                item(key = "visible-categories-empty") {
                    ManagementEmptyText("No visible categories.")
                }
            } else {
                items(
                    items = visibleCategories,
                    key = { category -> "visible-category:${category.providerCategoryKey}" },
                ) { category ->
                    CategoryManagementRow(
                        category = category,
                        selected = selectedCategory?.providerCategoryKey == category.providerCategoryKey,
                        onOpen = { selectCategory(category.providerCategoryKey) },
                        actionLabel = "Hide",
                        actionEnabled = !categoryMutationInFlight,
                        onAction = { setCategoryHidden(category, true) },
                    )
                }
            }

            item(key = "hidden-categories-toggle") {
                CollapsibleManagementHeader(
                    title = "Hidden Categories",
                    count = hiddenCategories.size,
                    expanded = hiddenCategoriesExpanded,
                    onToggle = { hiddenCategoriesExpanded = !hiddenCategoriesExpanded },
                )
            }
            if (hiddenCategoriesExpanded) {
                if (hiddenCategories.isEmpty()) {
                    item(key = "hidden-categories-empty") {
                        ManagementEmptyText("No hidden categories.")
                    }
                } else {
                    items(
                        items = hiddenCategories,
                        key = { category -> "hidden-category:${category.providerCategoryKey}" },
                    ) { category ->
                        CategoryManagementRow(
                            category = category,
                            selected = selectedCategory?.providerCategoryKey == category.providerCategoryKey,
                            onOpen = { selectCategory(category.providerCategoryKey) },
                            actionLabel = "Restore",
                            actionEnabled = !categoryMutationInFlight,
                            onAction = { setCategoryHidden(category, false) },
                        )
                    }
                }
            }

            item(key = "groups-divider") { HorizontalDivider() }
            item(key = "custom-groups-title") {
                ManagementSectionHeader(
                    title = "Custom Groups",
                    count = state.customGroups.size,
                    actionLabel = "Manage",
                    onAction = { showGroupManager = true },
                )
            }
            if (state.customGroups.isEmpty()) {
                item(key = "custom-groups-empty") {
                    ManagementEmptyText("No custom groups yet.")
                }
            } else {
                items(
                    items = state.customGroups,
                    key = { group -> "group:${group.groupId}" },
                ) { group ->
                    GroupManagementRow(
                        group = group,
                        selected = selectedGroup?.groupId == group.groupId,
                        onOpen = { selectGroup(group.groupId) },
                    )
                }
            }

            item(key = "channels-divider") { HorizontalDivider() }
            item(key = "channel-context") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            selectedGroup != null -> selectedGroup.name
                            selectedCategory != null -> selectedCategory.name
                            else -> "Channels"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.channels.size} channels in this management context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.query.searchTerm,
                        onValueChange = browseSession::updateSearch,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search channels") },
                    )
                }
            }

            item(key = "selection-actions") {
                ChannelManagementActions(
                    selectedCount = editState.selectedChannelIds.size,
                    selectedChannel = selectedChannel,
                    groups = state.customGroups,
                    canMoveUp = canMoveSelectedUp,
                    canMoveDown = canMoveSelectedDown,
                    onSelectVisible = {
                        editState = ChannelEditReducer.selectVisible(
                            state = editState,
                            visibleChannelIds = visibleChannels.map { channel -> channel.channelId },
                        )
                    },
                    onClearSelection = ::clearSelection,
                    onBulkAction = ::executeBulkAction,
                    onMoveUp = ::moveSelectedUp,
                    onMoveDown = ::moveSelectedDown,
                    onCustomize = { channel -> customizeTarget = channel },
                    onManageGroups = { showGroupManager = true },
                )
            }

            item(key = "visible-channels-title") {
                ManagementSectionHeader(
                    title = "Visible Channels",
                    count = visibleChannels.size,
                )
            }
            if (visibleChannels.isEmpty()) {
                item(key = "visible-channels-empty") {
                    ManagementEmptyText("No visible channels in this context.")
                }
            } else {
                items(
                    items = visibleChannels,
                    key = { channel -> "visible-channel:${channel.channelId}" },
                ) { channel ->
                    ChannelManagementRow(
                        channel = channel,
                        selected = channel.channelId in editState.selectedChannelIds,
                        onToggle = {
                            editState = ChannelEditReducer.toggleSelection(editState, channel.channelId)
                        },
                    )
                }
            }

            item(key = "hidden-channels-toggle") {
                CollapsibleManagementHeader(
                    title = "Hidden Channels",
                    count = hiddenChannels.size,
                    expanded = hiddenChannelsExpanded,
                    onToggle = { hiddenChannelsExpanded = !hiddenChannelsExpanded },
                )
            }
            if (hiddenChannelsExpanded) {
                if (hiddenChannels.isEmpty()) {
                    item(key = "hidden-channels-empty") {
                        ManagementEmptyText("No hidden channels in this context.")
                    }
                } else {
                    items(
                        items = hiddenChannels,
                        key = { channel -> "hidden-channel:${channel.channelId}" },
                    ) { channel ->
                        ChannelManagementRow(
                            channel = channel,
                            selected = channel.channelId in editState.selectedChannelIds,
                            onToggle = {
                                editState = ChannelEditReducer.toggleSelection(editState, channel.channelId)
                            },
                        )
                    }
                }
            }
        }
    }

    customizeTarget?.let { channel ->
        ChannelCustomizationDialog(
            channel = channel,
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
            onDismiss = { customizeTarget = null },
        )
    }

    if (showGroupManager) {
        CustomGroupManagerDialog(
            groups = state.customGroups,
            onCreateGroup = { name -> scope.launch { runtime.createCustomGroup(name) } },
            onRenameGroup = { groupId, name ->
                scope.launch { runtime.renameCustomGroup(groupId, name) }
            },
            onDeleteGroup = { groupId -> scope.launch { runtime.deleteCustomGroup(groupId) } },
            onDismiss = { showGroupManager = false },
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
private fun ManagementSectionHeader(
    title: String,
    count: Int,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun CollapsibleManagementHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title ($count)",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (expanded) "Hide" else "Show",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ManagementEmptyText(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryManagementRow(
    category: LiveCategory,
    selected: Boolean,
    onOpen: () -> Unit,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (category.isHidden) "Hidden category" else "Visible category",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onAction,
            enabled = actionEnabled,
        ) { Text(actionLabel) }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun GroupManagementRow(
    group: LiveCustomGroup,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Open",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun ChannelManagementRow(
    channel: LiveChannelItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            val metadata = buildList {
                if (channel.isFavorite) add("Favorite")
                if (channel.localDisplayName != null) add("Custom name")
                if (channel.hasLogoOverride) add("Custom logo")
                if (channel.isHidden) add("Hidden")
            }.joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun ChannelManagementActions(
    selectedCount: Int,
    selectedChannel: LiveChannelItem?,
    groups: List<LiveCustomGroup>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onBulkAction: (ChannelBulkAction) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCustomize: (LiveChannelItem) -> Unit,
    onManageGroups: () -> Unit,
) {
    val hasSelection = selectedCount > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$selectedCount selected",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSelectVisible) { Text("Select visible") }
            TextButton(onClick = onClearSelection, enabled = hasSelection) { Text("Clear") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.Hide) },
                enabled = hasSelection,
            ) { Text("Hide") }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.Unhide) },
                enabled = hasSelection,
            ) { Text("Restore") }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.Favorite) },
                enabled = hasSelection,
            ) { Text("Favorite") }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.RemoveFavorite) },
                enabled = hasSelection,
            ) { Text("Unfavorite") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onMoveUp, enabled = selectedCount == 1 && canMoveUp) {
                Text("Move up")
            }
            TextButton(onClick = onMoveDown, enabled = selectedCount == 1 && canMoveDown) {
                Text("Move down")
            }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.MoveToTop) },
                enabled = hasSelection,
            ) { Text("Top") }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.MoveToBottom) },
                enabled = hasSelection,
            ) { Text("Bottom") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.MoveFavoritesToTop) },
                enabled = hasSelection,
            ) { Text("Favorite top") }
            TextButton(
                onClick = { onBulkAction(ChannelBulkAction.MoveFavoritesToBottom) },
                enabled = hasSelection,
            ) { Text("Favorite bottom") }
            TextButton(
                onClick = { selectedChannel?.let(onCustomize) },
                enabled = selectedCount == 1 && selectedChannel != null,
            ) { Text("Customize") }
            TextButton(onClick = onManageGroups) { Text("Groups") }
        }
        if (groups.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GroupActionMenu(
                    label = "Add to group",
                    groups = groups,
                    enabled = hasSelection,
                    onGroupSelected = { groupId ->
                        onBulkAction(ChannelBulkAction.AddToGroup(groupId))
                    },
                )
                GroupActionMenu(
                    label = "Remove from group",
                    groups = groups,
                    enabled = hasSelection,
                    onGroupSelected = { groupId ->
                        onBulkAction(ChannelBulkAction.RemoveFromGroup(groupId))
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupActionMenu(
    label: String,
    groups: List<LiveCustomGroup>,
    enabled: Boolean,
    onGroupSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
        ) { Text(label) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        expanded = false
                        onGroupSelected(group.groupId)
                    },
                )
            }
        }
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
