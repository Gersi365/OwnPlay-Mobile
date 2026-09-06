package app.ownplay.player.ui.live

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.player.epg.EpgProgram
import app.ownplay.player.live.LiveBrowseOrder
import app.ownplay.player.live.LiveBrowseState
import app.ownplay.player.live.LiveCategory
import app.ownplay.player.ui.view.ContentViewMode

/**
 * Two-level Live browsing for TV: categories first, channels second.
 *
 * The channel level deliberately reuses the established List / Compact / Cards implementation so
 * hierarchy does not fork playback or personalization behavior. Back/ESC ownership lives in
 * [LiveBrowseHierarchyPolicy] and is applied by LiveRoute. Non-TV callers remain on the established
 * browse behavior and do not receive TV focus fallback semantics.
 */
@Composable
internal fun HierarchicalLiveBrowse(
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
    focusChannelId: String? = null,
    focusRequestGeneration: Int = 0,
    channelFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val useTvFocusFallback = isTelevision && channelFocusRequester != null
    val resolvedChannelFocusId = if (useTvFocusFallback) {
        focusChannelId?.takeIf { candidate ->
            state.channels.any { channel -> channel.channelId == candidate }
        } ?: state.channels.firstOrNull()?.channelId
    } else {
        focusChannelId
    }
    val resolvedFocusRequestGeneration = if (
        useTvFocusFallback && resolvedChannelFocusId != null
    ) {
        focusRequestGeneration.coerceAtLeast(1)
    } else {
        focusRequestGeneration
    }

    when (hierarchyLevel) {
        LiveBrowseHierarchyLevel.CATEGORIES -> LiveCategoryHierarchyPicker(
            state = state,
            onCategorySelected = onCategorySelected,
            modifier = modifier,
        )

        LiveBrowseHierarchyLevel.CHANNELS -> PortraitLiveBrowseWithViewModes(
            state = state,
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
            focusChannelId = resolvedChannelFocusId,
            focusRequestGeneration = resolvedFocusRequestGeneration,
            channelFocusRequester = channelFocusRequester,
            showCategoryStrip = !isTelevision,
            modifier = modifier,
        )
    }
}

@Composable
private fun LiveCategoryHierarchyPicker(
    state: LiveBrowseState,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isTelevision =
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val preferredCategoryKey = state.query.categoryKey?.takeIf { selected ->
        state.categories.any { category -> category.providerCategoryKey == selected }
    } ?: state.categories.firstOrNull()?.providerCategoryKey
    val preferredCategoryIndex = remember(preferredCategoryKey, state.categories) {
        state.categories.indexOfFirst { category ->
            category.providerCategoryKey == preferredCategoryKey
        }
    }
    val categoryListState = rememberLazyListState()
    val initialFocusRequester = remember(preferredCategoryKey, state.categories) { FocusRequester() }

    LaunchedEffect(
        isTelevision,
        preferredCategoryKey,
        preferredCategoryIndex,
        state.categories,
    ) {
        if (!isTelevision || preferredCategoryKey == null || preferredCategoryIndex < 0) {
            return@LaunchedEffect
        }
        categoryListState.scrollToItem(preferredCategoryIndex)
        withFrameNanos { }
        initialFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Live categories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Choose a category, then browse its channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            state = categoryListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                items = state.categories,
                key = LiveCategory::providerCategoryKey,
            ) { category ->
                val isPreferred = category.providerCategoryKey == preferredCategoryKey
                LiveCategoryHierarchyRow(
                    title = category.name,
                    subtitle = "Browse channels",
                    selected = isPreferred,
                    onClick = { onCategorySelected(category.providerCategoryKey) },
                    modifier = if (isPreferred) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveCategoryHierarchyRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(title) { mutableStateOf(false) }
    val emphasized = focused || selected

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        color = when {
            focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (emphasized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
