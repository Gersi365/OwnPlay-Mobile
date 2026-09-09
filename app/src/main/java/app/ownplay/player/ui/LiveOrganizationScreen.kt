package app.ownplay.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import app.ownplay.player.personalization.ChannelBulkActionExecutionResult
import app.ownplay.player.personalization.ChannelCustomizationMutationResult
import app.ownplay.player.personalization.ChannelVisibilityMutationResult
import app.ownplay.player.personalization.CustomGroupMutationResult
import app.ownplay.player.personalization.FavoriteMutationResult
import app.ownplay.player.personalization.ManualOrderMutationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class LiveOrganizationCategorySections(
    val visible: List<LiveCategory>,
    val hidden: List<LiveCategory>,
)

internal data class LiveOrganizationChannelSections(
    val visible: List<LiveChannelItem>,
    val hidden: List<LiveChannelItem>,
)

internal fun liveOrganizationCategorySections(
    categories: List<LiveCategory>,
): LiveOrganizationCategorySections = LiveOrganizationCategorySections(
    visible = categories.filterNot(LiveCategory::isHidden),
    hidden = categories.filter(LiveCategory::isHidden),
)

internal fun liveOrganizationChannelSections(
    channels: List<LiveChannelItem>,
    categoryKey: String,
): LiveOrganizationChannelSections {
    val categoryChannels = channels.filter { channel -> channel.categoryKey == categoryKey }
    return LiveOrganizationChannelSections(
        visible = categoryChannels.filterNot(LiveChannelItem::isChannelHidden),
        hidden = categoryChannels.filter(LiveChannelItem::isChannelHidden),
    )
}

internal fun liveCustomGroupMemberCount(
    channels: List<LiveChannelItem>,
    groupId: String,
): Int = channels.count { channel -> groupId in channel.customGroupIds }

internal fun channelBulkActionSucceeded(result: ChannelBulkActionExecutionResult): Boolean = when (result) {
    is ChannelBulkActionExecutionResult.Visibility ->
        result.result is ChannelVisibilityMutationResult.Success
    is ChannelBulkActionExecutionResult.Favorite ->
        result.result is FavoriteMutationResult.Success
    is ChannelBulkActionExecutionResult.ManualOrder ->
        result.result is ManualOrderMutationResult.Success
    is ChannelBulkActionExecutionResult.CustomGroup ->
        result.result is CustomGroupMutationResult.Success
}

private sealed interface GroupEditorTarget {
    data object Create : GroupEditorTarget
    data class Rename(val group: LiveCustomGroup) : GroupEditorTarget
}

private data class ChannelEditorTarget(
    val channel: LiveChannelItem,
)

@Composable
internal fun LiveOrganizationScreen(
    runtime: OwnPlayAppRuntime,
    summaries: List<PlaylistSourceSummary>,
    onBack: () -> Unit,
) {
    var selectedSourceId by remember(summaries) {
        mutableStateOf(summaries.firstOrNull()?.sourceId)
    }
    val resolvedSourceId = selectedSourceId

    if (resolvedSourceId == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Organize Live", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Add a playlist before organizing categories and channels.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VNextMediaSecondaryAction(
                label = "Settings",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onBack,
            )
        }
        return
    }

    val browseSession = remember(resolvedSourceId) { LiveBrowseSession() }
    val browseFlow = remember(resolvedSourceId) {
        browseSession.observe(runtime.observeLiveCatalog(resolvedSourceId))
    }
    val state by browseFlow.collectAsState(initial = LiveBrowseState())
    val scope = rememberCoroutineScope()

    var selectedCategoryKey by remember(resolvedSourceId) { mutableStateOf<String?>(null) }
    var hiddenCategoriesExpanded by remember(resolvedSourceId) { mutableStateOf(false) }
    var hiddenChannelsExpanded by remember(resolvedSourceId, selectedCategoryKey) {
        mutableStateOf(false)
    }
    var showCategoryReorder by remember(resolvedSourceId) { mutableStateOf(false) }
    var mutationKey by remember(resolvedSourceId) { mutableStateOf<String?>(null) }
    var mutationError by remember(resolvedSourceId) { mutableStateOf<String?>(null) }
    var groupEditorTarget by remember(resolvedSourceId) { mutableStateOf<GroupEditorTarget?>(null) }
    var deleteGroupTarget by remember(resolvedSourceId) { mutableStateOf<LiveCustomGroup?>(null) }
    var channelEditorTarget by remember(resolvedSourceId) { mutableStateOf<ChannelEditorTarget?>(null) }

    LaunchedEffect(resolvedSourceId) {
        browseSession.setIncludeHidden(true)
        browseSession.setOrder(LiveBrowseOrder.MY_ORDER)
    }

    LaunchedEffect(summaries, resolvedSourceId) {
        if (summaries.none { summary -> summary.sourceId == resolvedSourceId }) {
            selectedSourceId = summaries.firstOrNull()?.sourceId
        }
    }

    val categorySections = remember(state.categories) {
        liveOrganizationCategorySections(state.categories)
    }
    val selectedCategory = selectedCategoryKey?.let { key ->
        state.categories.firstOrNull { category -> category.providerCategoryKey == key }
    }
    val channelSections = remember(state.channels, selectedCategoryKey) {
        selectedCategoryKey?.let { key ->
            liveOrganizationChannelSections(state.channels, key)
        }
    }

    BackHandler(enabled = selectedCategoryKey != null) {
        selectedCategoryKey = null
    }

    fun runMutation(
        key: String,
        errorMessage: String,
        block: suspend () -> Boolean,
    ) {
        if (mutationKey != null) return
        mutationKey = key
        mutationError = null
        scope.launch {
            try {
                if (!block()) mutationError = errorMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutationError = errorMessage
            } finally {
                mutationKey = null
            }
        }
    }

    fun setCategoryHidden(category: LiveCategory, hidden: Boolean) {
        runMutation(
            key = "category:${category.providerCategoryKey}",
            errorMessage = "Could not save category visibility.",
        ) {
            val result = if (hidden) {
                runtime.hideCategory(resolvedSourceId, category.providerCategoryKey)
            } else {
                runtime.unhideCategory(resolvedSourceId, category.providerCategoryKey)
            }
            result is CategoryVisibilityMutationResult.Success
        }
    }

    fun runChannelAction(
        channel: LiveChannelItem,
        action: ChannelBulkAction,
        errorMessage: String,
    ) {
        runMutation(
            key = "channel:${channel.channelId}:${action::class.simpleName}",
            errorMessage = errorMessage,
        ) {
            channelBulkActionSucceeded(
                runtime.executeChannelBulkAction(
                    sourceId = resolvedSourceId,
                    selectedChannelIds = setOf(channel.channelId),
                    action = action,
                ),
            )
        }
    }

    val header: @Composable () -> Unit = {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedCategoryKey != null) {
                    VNextMediaIconAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = { selectedCategoryKey = null },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedCategory?.name ?: "Organize Live",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (selectedCategory == null) {
                            "Categories, hidden content and custom groups"
                        } else {
                            "Visible and hidden channels"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selectedCategory == null) {
                    LiveOrganizationSourceMenu(
                        summaries = summaries,
                        selectedSourceId = resolvedSourceId,
                        onSelected = { sourceId ->
                            selectedSourceId = sourceId
                            selectedCategoryKey = null
                        },
                    )
                    VNextMediaSecondaryAction(
                        label = "Done",
                        onClick = onBack,
                    )
                } else {
                    VNextMediaPill(
                        label = if (selectedCategory.isHidden) "Unhide category" else "Hide category",
                        selected = selectedCategory.isHidden,
                        enabled = mutationKey == null,
                        onClick = {
                            setCategoryHidden(selectedCategory, hidden = !selectedCategory.isHidden)
                        },
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        header()

        mutationError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (selectedCategory == null || channelSections == null) {
            LiveOrganizationRoot(
                categories = categorySections,
                customGroups = state.customGroups,
                channels = state.channels,
                hiddenExpanded = hiddenCategoriesExpanded,
                busy = mutationKey != null,
                onToggleHidden = { hiddenCategoriesExpanded = !hiddenCategoriesExpanded },
                onOpenCategory = { category ->
                    mutationError = null
                    selectedCategoryKey = category.providerCategoryKey
                },
                onSetCategoryHidden = ::setCategoryHidden,
                onReorderVisibleCategories = {
                    mutationError = null
                    showCategoryReorder = true
                },
                onCreateGroup = { groupEditorTarget = GroupEditorTarget.Create },
                onRenameGroup = { group -> groupEditorTarget = GroupEditorTarget.Rename(group) },
                onDeleteGroup = { group -> deleteGroupTarget = group },
                modifier = Modifier.weight(1f),
            )
        } else {
            LiveOrganizationCategoryDetail(
                channelSections = channelSections,
                customGroups = state.customGroups,
                hiddenExpanded = hiddenChannelsExpanded,
                busy = mutationKey != null,
                onToggleHidden = { hiddenChannelsExpanded = !hiddenChannelsExpanded },
                onHideChannel = { channel ->
                    runChannelAction(
                        channel = channel,
                        action = ChannelBulkAction.Hide,
                        errorMessage = "Could not hide channel.",
                    )
                },
                onUnhideChannel = { channel ->
                    runChannelAction(
                        channel = channel,
                        action = ChannelBulkAction.Unhide,
                        errorMessage = "Could not unhide channel.",
                    )
                },
                onFavoriteChange = { channel, favorite ->
                    runChannelAction(
                        channel = channel,
                        action = if (favorite) {
                            ChannelBulkAction.Favorite
                        } else {
                            ChannelBulkAction.RemoveFavorite
                        },
                        errorMessage = "Could not update favorite state.",
                    )
                },
                onMoveToTop = { channel ->
                    runChannelAction(
                        channel = channel,
                        action = ChannelBulkAction.MoveToTop,
                        errorMessage = "Could not move channel.",
                    )
                },
                onMoveToBottom = { channel ->
                    runChannelAction(
                        channel = channel,
                        action = ChannelBulkAction.MoveToBottom,
                        errorMessage = "Could not move channel.",
                    )
                },
                onGroupMembershipChange = { channel, group, member ->
                    runChannelAction(
                        channel = channel,
                        action = if (member) {
                            ChannelBulkAction.AddToGroup(group.groupId)
                        } else {
                            ChannelBulkAction.RemoveFromGroup(group.groupId)
                        },
                        errorMessage = "Could not update custom group membership.",
                    )
                },
                onEditChannel = { channel -> channelEditorTarget = ChannelEditorTarget(channel) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showCategoryReorder) {
        CategoryReorderSheet(
            categories = categorySections.visible,
            onOrderChanged = { visibleOrderedKeys ->
                val hiddenKeys = categorySections.hidden.map(LiveCategory::providerCategoryKey)
                runMutation(
                    key = "category-order",
                    errorMessage = "Could not save category order.",
                ) {
                    runtime.setCategoryOrder(
                        sourceId = resolvedSourceId,
                        orderedCategoryKeys = visibleOrderedKeys + hiddenKeys,
                    ) is CategoryOrderMutationResult.Success
                }
            },
            onDismiss = { showCategoryReorder = false },
        )
    }

    groupEditorTarget?.let { target ->
        LiveOrganizationGroupEditorDialog(
            target = target,
            busy = mutationKey != null,
            onDismiss = { groupEditorTarget = null },
            onSave = { name ->
                runMutation(
                    key = "group-editor",
                    errorMessage = "Could not save custom group.",
                ) {
                    val result = when (target) {
                        GroupEditorTarget.Create -> runtime.createCustomGroup(name)
                        is GroupEditorTarget.Rename -> runtime.renameCustomGroup(target.group.groupId, name)
                    }
                    val success = result is CustomGroupMutationResult.Success
                    if (success) groupEditorTarget = null
                    success
                }
            },
        )
    }

    deleteGroupTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { if (mutationKey == null) deleteGroupTarget = null },
            title = { Text("Delete custom group?") },
            text = { Text("This removes the group only. Channels and provider content are not deleted.") },
            confirmButton = {
                TextButton(
                    enabled = mutationKey == null,
                    onClick = {
                        runMutation(
                            key = "delete-group:${group.groupId}",
                            errorMessage = "Could not delete custom group.",
                        ) {
                            val success = runtime.deleteCustomGroup(group.groupId) is CustomGroupMutationResult.Success
                            if (success) deleteGroupTarget = null
                            success
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = mutationKey == null,
                    onClick = { deleteGroupTarget = null },
                ) { Text("Cancel") }
            },
        )
    }

    channelEditorTarget?.let { target ->
        LiveOrganizationChannelEditorDialog(
            channel = target.channel,
            busy = mutationKey != null,
            onDismiss = { channelEditorTarget = null },
            onSave = { localName, replacementLogo, clearExistingLogo ->
                runMutation(
                    key = "edit-channel:${target.channel.channelId}",
                    errorMessage = "Could not save channel personalization.",
                ) {
                    val channel = target.channel
                    val nameResult = if (localName.isBlank()) {
                        runtime.clearLocalDisplayName(resolvedSourceId, channel.channelId)
                    } else {
                        runtime.setLocalDisplayName(resolvedSourceId, channel.channelId, localName)
                    }
                    if (nameResult !is ChannelCustomizationMutationResult.Success) {
                        return@runMutation false
                    }

                    val logoSucceeded = when {
                        replacementLogo != null ->
                            runtime.setLogoOverride(
                                resolvedSourceId,
                                channel.channelId,
                                replacementLogo,
                            ) is ChannelCustomizationMutationResult.Success
                        clearExistingLogo ->
                            runtime.clearLogoOverride(
                                resolvedSourceId,
                                channel.channelId,
                            ) is ChannelCustomizationMutationResult.Success
                        else -> true
                    }
                    if (logoSucceeded) channelEditorTarget = null
                    logoSucceeded
                }
            },
        )
    }
}

@Composable
private fun LiveOrganizationRoot(
    categories: LiveOrganizationCategorySections,
    customGroups: List<LiveCustomGroup>,
    channels: List<LiveChannelItem>,
    hiddenExpanded: Boolean,
    busy: Boolean,
    onToggleHidden: () -> Unit,
    onOpenCategory: (LiveCategory) -> Unit,
    onSetCategoryHidden: (LiveCategory, Boolean) -> Unit,
    onReorderVisibleCategories: () -> Unit,
    onCreateGroup: () -> Unit,
    onRenameGroup: (LiveCustomGroup) -> Unit,
    onDeleteGroup: (LiveCustomGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "visible-header") {
            OrganizationSectionHeader(
                title = "Visible Categories",
                count = categories.visible.size,
                actionLabel = "Reorder",
                onAction = onReorderVisibleCategories,
                actionEnabled = !busy && categories.visible.size > 1,
            )
        }
        items(
            items = categories.visible,
            key = { category -> "visible:${category.providerCategoryKey}" },
        ) { category ->
            OrganizationCategoryRow(
                category = category,
                hidden = false,
                busy = busy,
                onOpen = { onOpenCategory(category) },
                onVisibilityChange = { onSetCategoryHidden(category, true) },
            )
        }

        item(key = "hidden-header") {
            OrganizationExpandableHeader(
                title = "Hidden Categories",
                count = categories.hidden.size,
                expanded = hiddenExpanded,
                onToggle = onToggleHidden,
            )
        }
        if (hiddenExpanded) {
            items(
                items = categories.hidden,
                key = { category -> "hidden:${category.providerCategoryKey}" },
            ) { category ->
                OrganizationCategoryRow(
                    category = category,
                    hidden = true,
                    busy = busy,
                    onOpen = { onOpenCategory(category) },
                    onVisibilityChange = { onSetCategoryHidden(category, false) },
                )
            }
        }

        item(key = "groups-header") {
            OrganizationSectionHeader(
                title = "Custom Groups",
                count = customGroups.size,
                actionLabel = "New",
                onAction = onCreateGroup,
                actionEnabled = !busy,
            )
        }
        if (customGroups.isEmpty()) {
            item(key = "groups-empty") {
                OrganizationEmptyText("Create a custom group, then add channels from a category.")
            }
        } else {
            items(
                items = customGroups,
                key = { group -> "group:${group.groupId}" },
            ) { group ->
                OrganizationCustomGroupRow(
                    group = group,
                    memberCount = liveCustomGroupMemberCount(channels, group.groupId),
                    busy = busy,
                    onRename = { onRenameGroup(group) },
                    onDelete = { onDeleteGroup(group) },
                )
            }
        }
    }
}

@Composable
private fun LiveOrganizationCategoryDetail(
    channelSections: LiveOrganizationChannelSections,
    customGroups: List<LiveCustomGroup>,
    hiddenExpanded: Boolean,
    busy: Boolean,
    onToggleHidden: () -> Unit,
    onHideChannel: (LiveChannelItem) -> Unit,
    onUnhideChannel: (LiveChannelItem) -> Unit,
    onFavoriteChange: (LiveChannelItem, Boolean) -> Unit,
    onMoveToTop: (LiveChannelItem) -> Unit,
    onMoveToBottom: (LiveChannelItem) -> Unit,
    onGroupMembershipChange: (LiveChannelItem, LiveCustomGroup, Boolean) -> Unit,
    onEditChannel: (LiveChannelItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "visible-channel-header") {
            OrganizationSectionHeader(
                title = "Visible Channels",
                count = channelSections.visible.size,
            )
        }
        if (channelSections.visible.isEmpty()) {
            item(key = "visible-channel-empty") {
                OrganizationEmptyText("No visible channels in this category.")
            }
        } else {
            items(
                items = channelSections.visible,
                key = { channel -> "visible:${channel.channelId}" },
            ) { channel ->
                OrganizationChannelRow(
                    channel = channel,
                    customGroups = customGroups,
                    hidden = false,
                    busy = busy,
                    onVisibilityChange = { onHideChannel(channel) },
                    onFavoriteChange = { favorite -> onFavoriteChange(channel, favorite) },
                    onMoveToTop = { onMoveToTop(channel) },
                    onMoveToBottom = { onMoveToBottom(channel) },
                    onGroupMembershipChange = { group, member ->
                        onGroupMembershipChange(channel, group, member)
                    },
                    onEdit = { onEditChannel(channel) },
                )
            }
        }

        item(key = "hidden-channel-header") {
            OrganizationExpandableHeader(
                title = "Hidden Channels",
                count = channelSections.hidden.size,
                expanded = hiddenExpanded,
                onToggle = onToggleHidden,
            )
        }
        if (hiddenExpanded) {
            if (channelSections.hidden.isEmpty()) {
                item(key = "hidden-channel-empty") {
                    OrganizationEmptyText("No hidden channels in this category.")
                }
            } else {
                items(
                    items = channelSections.hidden,
                    key = { channel -> "hidden:${channel.channelId}" },
                ) { channel ->
                    OrganizationChannelRow(
                        channel = channel,
                        customGroups = customGroups,
                        hidden = true,
                        busy = busy,
                        onVisibilityChange = { onUnhideChannel(channel) },
                        onFavoriteChange = { favorite -> onFavoriteChange(channel, favorite) },
                        onMoveToTop = { onMoveToTop(channel) },
                        onMoveToBottom = { onMoveToBottom(channel) },
                        onGroupMembershipChange = { group, member ->
                            onGroupMembershipChange(channel, group, member)
                        },
                        onEdit = { onEditChannel(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizationSectionHeader(
    title: String,
    count: Int,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 2.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$title · $count",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionLabel != null && onAction != null) {
            VNextMediaPill(
                label = actionLabel,
                selected = false,
                enabled = actionEnabled,
                onClick = onAction,
            )
        }
    }
}

@Composable
private fun OrganizationExpandableHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$title · $count",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrganizationCategoryRow(
    category: LiveCategory,
    hidden: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onVisibilityChange: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (hidden) "Hidden category" else "Open channel organization",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            VNextMediaPill(
                label = if (hidden) "Unhide" else "Hide",
                selected = hidden,
                enabled = !busy,
                onClick = onVisibilityChange,
            )
        }
    }
}

@Composable
private fun OrganizationCustomGroupRow(
    group: LiveCustomGroup,
    memberCount: Int,
    busy: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(group.groupId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$memberCount channels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column {
                VNextMediaIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Group actions",
                    enabled = !busy,
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizationChannelRow(
    channel: LiveChannelItem,
    customGroups: List<LiveCustomGroup>,
    hidden: Boolean,
    busy: Boolean,
    onVisibilityChange: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onGroupMembershipChange: (LiveCustomGroup, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    var menuExpanded by remember(channel.channelId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (channel.isFavorite) append("Favorite")
                        if (channel.localDisplayName != null) {
                            if (isNotEmpty()) append(" · ")
                            append("Local name")
                        }
                        if (channel.hasLogoOverride) {
                            if (isNotEmpty()) append(" · ")
                            append("Custom logo")
                        }
                        if (isEmpty()) append(if (hidden) "Hidden" else "Visible")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VNextMediaPill(
                label = if (hidden) "Unhide" else "Hide",
                selected = hidden,
                enabled = !busy,
                onClick = onVisibilityChange,
            )
            Column {
                VNextMediaIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Channel actions",
                    enabled = !busy,
                    onClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit name / logo") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (channel.isFavorite) "Remove favorite" else "Favorite") },
                        onClick = {
                            menuExpanded = false
                            onFavoriteChange(!channel.isFavorite)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to top") },
                        onClick = {
                            menuExpanded = false
                            onMoveToTop()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to bottom") },
                        onClick = {
                            menuExpanded = false
                            onMoveToBottom()
                        },
                    )
                    customGroups.forEach { group ->
                        val member = group.groupId in channel.customGroupIds
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member) {
                                        "Remove from ${group.name}"
                                    } else {
                                        "Add to ${group.name}"
                                    },
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onGroupMembershipChange(group, !member)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizationEmptyText(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveOrganizationSourceMenu(
    summaries: List<PlaylistSourceSummary>,
    selectedSourceId: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = summaries.firstOrNull { summary -> summary.sourceId == selectedSourceId }
    Column {
        VNextMediaPill(
            label = selected?.name ?: "Source",
            selected = false,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            summaries.forEach { summary ->
                DropdownMenuItem(
                    text = { Text(summary.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelected(summary.sourceId)
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveOrganizationGroupEditorDialog(
    target: GroupEditorTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(target) {
        mutableStateOf(
            when (target) {
                GroupEditorTarget.Create -> ""
                is GroupEditorTarget.Rename -> target.group.name
            },
        )
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (target) {
                    GroupEditorTarget.Create -> "New custom group"
                    is GroupEditorTarget.Rename -> "Rename custom group"
                },
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onSave(name.trim()) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LiveOrganizationChannelEditorDialog(
    channel: LiveChannelItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        localName: String,
        replacementLogo: String?,
        clearExistingLogo: Boolean,
    ) -> Unit,
) {
    var localName by remember(channel.channelId) {
        mutableStateOf(channel.localDisplayName.orEmpty())
    }
    var replacementLogo by remember(channel.channelId) { mutableStateOf("") }
    var clearExistingLogo by remember(channel.channelId) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Channel personalization") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = localName,
                    onValueChange = { localName = it },
                    label = { Text("Local name") },
                    supportingText = { Text("Leave blank to use the provider name.") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = replacementLogo,
                    onValueChange = { next ->
                        replacementLogo = next
                        if (next.isNotBlank()) clearExistingLogo = false
                    },
                    label = { Text("Replace logo") },
                    supportingText = {
                        Text(
                            if (channel.hasLogoOverride) {
                                "Leave blank to keep the current custom logo."
                            } else {
                                "Leave blank to keep the provider logo."
                            },
                        )
                    },
                    singleLine = true,
                )
                if (channel.hasLogoOverride) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            clearExistingLogo = !clearExistingLogo
                            if (clearExistingLogo) replacementLogo = ""
                        },
                    ) {
                        Text(
                            if (clearExistingLogo) {
                                "Keep current custom logo"
                            } else {
                                "Clear custom logo"
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    onSave(
                        localName.trim(),
                        replacementLogo.trim().takeIf(String::isNotBlank),
                        clearExistingLogo,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
