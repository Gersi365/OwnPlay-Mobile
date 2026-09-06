package app.ownplay.player.playback

import app.ownplay.player.live.LiveCategory
import app.ownplay.player.live.LiveChannelItem

data class LivePlaybackBrowseEntry(
    val channelId: String,
    val displayName: String,
) {
    init {
        require(channelId.isNotBlank()) { "Channel ID must not be blank" }
    }

    override fun toString(): String =
        "LivePlaybackBrowseEntry(channelId=<opaque>, displayName=$displayName)"
}

data class LivePlaybackCategoryEntry(
    val categoryKey: String,
    val displayName: String,
    val entries: List<LivePlaybackBrowseEntry>,
) {
    init {
        require(categoryKey.isNotBlank()) { "Category key must not be blank" }
        require(entries.isNotEmpty()) { "Category navigation entries must not be empty" }
        require(entries.map(LivePlaybackBrowseEntry::channelId).distinct().size == entries.size) {
            "Category navigation channel IDs must be unique"
        }
    }

    override fun toString(): String =
        "LivePlaybackCategoryEntry(categoryKey=<opaque>, displayName=$displayName, entryCount=${entries.size})"
}

data class LivePlaybackBrowseContext(
    val sourceId: String,
    val entries: List<LivePlaybackBrowseEntry>,
    val activeCategoryKey: String? = null,
    val categories: List<LivePlaybackCategoryEntry> = emptyList(),
) {
    init {
        require(sourceId.isNotBlank()) { "Source ID must not be blank" }
        require(entries.map(LivePlaybackBrowseEntry::channelId).distinct().size == entries.size) {
            "Browse context channel IDs must be unique"
        }
        require(categories.map(LivePlaybackCategoryEntry::categoryKey).distinct().size == categories.size) {
            "Browse context category keys must be unique"
        }
    }

    fun selectionFor(channelId: String): LivePlaybackSelection? {
        val index = entries.indexOfFirst { entry -> entry.channelId == channelId }
        if (index < 0) return null
        val entry = entries[index]
        return LivePlaybackSelection(
            request = PlaybackRequest(
                sourceId = sourceId,
                channelId = entry.channelId,
                navigationContext = PlaybackNavigationContext(
                    previousChannelId = entries.getOrNull(index - 1)?.channelId,
                    nextChannelId = entries.getOrNull(index + 1)?.channelId,
                ),
            ),
            displayName = entry.displayName,
            browseContext = this,
        )
    }

    fun categorySelection(direction: PlaybackNavigationDirection): LivePlaybackSelection? {
        val currentIndex = categories.indexOfFirst { category ->
            category.categoryKey == activeCategoryKey
        }
        if (currentIndex < 0) return null
        val targetIndex = when (direction) {
            PlaybackNavigationDirection.PREVIOUS -> currentIndex - 1
            PlaybackNavigationDirection.NEXT -> currentIndex + 1
        }
        val targetCategory = categories.getOrNull(targetIndex) ?: return null
        val targetEntry = targetCategory.entries.firstOrNull() ?: return null
        val targetContext = copy(
            entries = targetCategory.entries,
            activeCategoryKey = targetCategory.categoryKey,
        )
        return targetContext.selectionFor(targetEntry.channelId)
    }

    override fun toString(): String =
        "LivePlaybackBrowseContext(sourceId=<opaque>, entryCount=${entries.size}, categoryCount=${categories.size})"

    companion object {
        fun capture(
            sourceId: String,
            visibleChannels: List<LiveChannelItem>,
            categories: List<LiveCategory> = emptyList(),
            categoryNavigationChannels: List<LiveChannelItem> = visibleChannels,
            activeCategoryKey: String? = null,
        ): LivePlaybackBrowseContext {
            val currentEntries = browseEntries(
                sourceId = sourceId,
                channels = visibleChannels,
            )
            val categoryEntries = categories.mapNotNull { category ->
                val entries = browseEntries(
                    sourceId = sourceId,
                    channels = categoryNavigationChannels.filter { channel ->
                        channel.categoryKey == category.providerCategoryKey
                    },
                )
                entries.takeIf { it.isNotEmpty() }?.let {
                    LivePlaybackCategoryEntry(
                        categoryKey = category.providerCategoryKey,
                        displayName = category.name,
                        entries = it,
                    )
                }
            }
            return LivePlaybackBrowseContext(
                sourceId = sourceId,
                entries = currentEntries,
                activeCategoryKey = activeCategoryKey?.takeIf { key ->
                    categoryEntries.any { category -> category.categoryKey == key }
                },
                categories = categoryEntries,
            )
        }

        private fun browseEntries(
            sourceId: String,
            channels: List<LiveChannelItem>,
        ): List<LivePlaybackBrowseEntry> = channels.asSequence()
            .filter { channel -> channel.sourceId == sourceId }
            .distinctBy(LiveChannelItem::channelId)
            .map { channel ->
                LivePlaybackBrowseEntry(
                    channelId = channel.channelId,
                    displayName = normalizedDisplayName(channel.displayName),
                )
            }
            .toList()
    }
}

data class LivePlaybackSelection(
    val request: PlaybackRequest,
    val displayName: String,
    private val browseContext: LivePlaybackBrowseContext? = null,
) {
    init {
        require(request.mediaKind == PlaybackMediaKind.LIVE) {
            "Live playback selection requires LIVE media kind"
        }
    }

    val categoryKey: String?
        get() = browseContext?.activeCategoryKey

    fun navigate(direction: PlaybackNavigationDirection): LivePlaybackSelection? {
        val targetChannelId = request.navigationTarget(direction) ?: return null
        return browseContext?.selectionFor(targetChannelId)
    }

    fun navigateCategory(direction: PlaybackNavigationDirection): LivePlaybackSelection? =
        browseContext?.categorySelection(direction)

    override fun toString(): String =
        "LivePlaybackSelection(request=$request, displayName=$displayName)"

    companion object {
        fun from(channel: LiveChannelItem): LivePlaybackSelection = LivePlaybackSelection(
            request = PlaybackRequest(
                sourceId = channel.sourceId,
                channelId = channel.channelId,
            ),
            displayName = normalizedDisplayName(channel.displayName),
        )
    }
}

sealed interface LiveChannelSelectionAction {
    data class StartPlayback(
        val selection: LivePlaybackSelection,
    ) : LiveChannelSelectionAction

    data class ToggleEditSelection(
        val channelId: String,
    ) : LiveChannelSelectionAction {
        override fun toString(): String = "ToggleEditSelection(channelId=<opaque>)"
    }
}

object LiveChannelSelectionRouter {
    fun route(
        channel: LiveChannelItem,
        isEditing: Boolean,
        browseContext: LivePlaybackBrowseContext? = null,
    ): LiveChannelSelectionAction = if (isEditing) {
        LiveChannelSelectionAction.ToggleEditSelection(channel.channelId)
    } else {
        LiveChannelSelectionAction.StartPlayback(
            selection = browseContext
                ?.takeIf { context -> context.sourceId == channel.sourceId }
                ?.selectionFor(channel.channelId)
                ?: LivePlaybackSelection.from(channel),
        )
    }
}

private fun normalizedDisplayName(value: String): String =
    value.trim().ifBlank { "Live channel" }
