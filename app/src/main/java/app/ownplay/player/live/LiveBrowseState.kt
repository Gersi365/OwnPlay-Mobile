package app.ownplay.player.live

import app.ownplay.player.persistence.ChannelAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

data class LiveBrowseState(
    val categories: List<LiveCategory> = emptyList(),
    val customGroups: List<LiveCustomGroup> = emptyList(),
    val channels: List<LiveChannelItem> = emptyList(),
    val categoryNavigationChannels: List<LiveChannelItem> = emptyList(),
    val channelCategoryKeyById: Map<String, String?> = emptyMap(),
    val catalogChannelCount: Int = 0,
    val query: LiveBrowseQuery = LiveBrowseQuery(),
)

internal class LiveBrowseStateProjector {
    private var cachedSnapshot: LiveCatalogSnapshot? = null
    private var cachedIncludeHidden: Boolean? = null
    private var cachedNavigationQuery: LiveBrowseQuery? = null

    private var cachedCategories: List<LiveCategory> = emptyList()
    private var cachedCustomGroups: List<LiveCustomGroup> = emptyList()
    private var cachedNavigationChannels: List<LiveChannelItem> = emptyList()
    private var cachedChannelCategoryKeyById: Map<String, String?> = emptyMap()
    private var cachedCatalogChannelCount: Int = 0

    fun project(
        snapshot: LiveCatalogSnapshot,
        currentQuery: LiveBrowseQuery,
    ): LiveBrowseState {
        val snapshotChanged = snapshot !== cachedSnapshot

        if (snapshotChanged) {
            cachedCustomGroups = snapshot.customGroups.sortedBy(LiveCustomGroup::groupOrder)
            cachedChannelCategoryKeyById = snapshot.channels.associate { channel ->
                channel.channelId to channel.providerCategoryKey
            }
            cachedCatalogChannelCount = snapshot.channels.count { channel ->
                channel.availability != ChannelAvailability.REMOVED
            }
        }

        if (snapshotChanged || cachedIncludeHidden != currentQuery.includeHidden) {
            cachedCategories = snapshot.categories
                .asSequence()
                .filter { category -> currentQuery.includeHidden || !category.isHidden }
                .sortedWith(
                    compareBy<LiveCategory> { it.manualOrder == null }
                        .thenBy { it.manualOrder ?: Long.MAX_VALUE }
                        .thenBy(LiveCategory::providerOrder),
                )
                .toList()
            cachedIncludeHidden = currentQuery.includeHidden
        }

        val navigationQuery = currentQuery.copy(
            searchTerm = "",
            categoryKey = null,
        )
        if (snapshotChanged || navigationQuery != cachedNavigationQuery) {
            cachedNavigationChannels = LiveBrowseProjector.project(
                records = snapshot.channels,
                query = navigationQuery,
                customGroupIdsByChannelId = snapshot.customGroupIdsByChannelId,
                hiddenCategoryKeys = snapshot.hiddenCategoryKeys,
            )
            cachedNavigationQuery = navigationQuery
        }

        cachedSnapshot = snapshot

        return LiveBrowseState(
            categories = cachedCategories,
            customGroups = cachedCustomGroups,
            channels = LiveBrowseProjector.filterTransient(
                navigationChannels = cachedNavigationChannels,
                query = currentQuery,
            ),
            categoryNavigationChannels = cachedNavigationChannels,
            channelCategoryKeyById = cachedChannelCategoryKeyById,
            catalogChannelCount = cachedCatalogChannelCount,
            query = currentQuery,
        )
    }
}

class LiveBrowseSession(
    initialQuery: LiveBrowseQuery = LiveBrowseQuery(),
) {
    private val query = MutableStateFlow(initialQuery)

    fun observe(catalog: Flow<LiveCatalogSnapshot>): Flow<LiveBrowseState> = flow {
        val projector = LiveBrowseStateProjector()
        combine(
            catalog,
            query,
        ) { snapshot, currentQuery ->
            projector.project(snapshot, currentQuery)
        }.collect { state ->
            emit(state)
        }
    }

    fun updateSearch(searchTerm: String) {
        query.update { current -> current.copy(searchTerm = searchTerm) }
    }

    fun selectCategory(categoryKey: String?) {
        query.update { current -> current.copy(categoryKey = categoryKey) }
    }

    fun selectCustomGroup(groupId: String?) {
        query.update { current -> current.copy(customGroupId = groupId) }
    }

    fun setFavoritesOnly(enabled: Boolean) {
        query.update { current -> current.copy(favoritesOnly = enabled) }
    }

    fun setHiddenOnly(
        enabled: Boolean,
        includeHiddenWhenDisabled: Boolean = false,
    ) {
        query.update { current ->
            current.copy(
                hiddenOnly = enabled,
                includeHidden = enabled || includeHiddenWhenDisabled,
            )
        }
    }

    fun setOrder(order: LiveBrowseOrder) {
        query.update { current -> current.copy(order = order) }
    }

    fun setIncludeHidden(enabled: Boolean) {
        query.update { current ->
            current.copy(
                includeHidden = enabled,
                hiddenOnly = if (enabled) current.hiddenOnly else false,
            )
        }
    }

    fun setIncludeRemoved(enabled: Boolean) {
        query.update { current -> current.copy(includeRemoved = enabled) }
    }
}
