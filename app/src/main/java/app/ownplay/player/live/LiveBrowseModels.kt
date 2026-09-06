package app.ownplay.player.live

import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.live.LiveChannelRecord
import java.util.Locale

data class LiveCategory(
    val providerCategoryKey: String,
    val name: String,
    val providerOrder: Long,
    val manualOrder: Long? = null,
    val isHidden: Boolean = false,
)

data class LiveChannelItem(
    val channelId: String,
    val sourceId: String,
    val categoryKey: String?,
    val categoryName: String?,
    val providerName: String,
    val localDisplayName: String?,
    val displayName: String,
    val logoRef: String?,
    val hasLogoOverride: Boolean,
    val providerOrder: Long,
    val manualOrder: Long?,
    val favoriteOrder: Long?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val availability: String,
    val recentAtEpochMillis: Long?,
    val customGroupIds: Set<String> = emptySet(),
)

enum class LiveBrowseOrder {
    PROVIDER,
    MY_ORDER,
    FAVORITE_ORDER,
    RECENTLY_WATCHED,
    A_TO_Z,
    Z_TO_A,
    CATEGORY,
}

data class LiveBrowseQuery(
    val searchTerm: String = "",
    val categoryKey: String? = null,
    val customGroupId: String? = null,
    val favoritesOnly: Boolean = false,
    val hiddenOnly: Boolean = false,
    val includeHidden: Boolean = false,
    val includeRemoved: Boolean = false,
    val order: LiveBrowseOrder = LiveBrowseOrder.PROVIDER,
)

object LiveBrowseProjector {
    fun project(
        records: List<LiveChannelRecord>,
        query: LiveBrowseQuery,
        customGroupIdsByChannelId: Map<String, Set<String>> = emptyMap(),
        hiddenCategoryKeys: Set<String> = emptySet(),
    ): List<LiveChannelItem> {
        val normalizedSearch = query.searchTerm.trim().lowercase(Locale.ROOT)

        val filtered = records.asSequence()
            .filter { record ->
                val hidden = isEffectivelyHidden(record, hiddenCategoryKeys)
                if (query.hiddenOnly) hidden else query.includeHidden || !hidden
            }
            .filter { record ->
                query.includeRemoved || record.availability != ChannelAvailability.REMOVED
            }
            .filter { record ->
                query.categoryKey == null || record.providerCategoryKey == query.categoryKey
            }
            .filter { record ->
                query.customGroupId == null ||
                    query.customGroupId in customGroupIdsByChannelId[record.channelId].orEmpty()
            }
            .filter { record -> !query.favoritesOnly || record.favoriteOrder != null }
            .filter { record -> matchesSearch(record, normalizedSearch) }
            .map { record ->
                toItem(
                    record = record,
                    customGroupIds = customGroupIdsByChannelId[record.channelId].orEmpty(),
                    hiddenCategoryKeys = hiddenCategoryKeys,
                )
            }
            .toList()

        return when (query.order) {
            LiveBrowseOrder.PROVIDER -> filtered.sortedBy(LiveChannelItem::providerOrder)
            LiveBrowseOrder.MY_ORDER -> filtered.sortedWith(
                compareBy<LiveChannelItem> { it.manualOrder == null }
                    .thenBy { it.manualOrder ?: Long.MAX_VALUE }
                    .thenBy(LiveChannelItem::providerOrder),
            )
            LiveBrowseOrder.FAVORITE_ORDER -> filtered.sortedWith(
                compareBy<LiveChannelItem> { it.favoriteOrder == null }
                    .thenBy { it.favoriteOrder ?: Long.MAX_VALUE }
                    .thenBy(LiveChannelItem::providerOrder),
            )
            LiveBrowseOrder.RECENTLY_WATCHED -> filtered.sortedWith(
                compareBy<LiveChannelItem> { it.recentAtEpochMillis == null }
                    .thenByDescending { it.recentAtEpochMillis ?: Long.MIN_VALUE }
                    .thenBy(LiveChannelItem::providerOrder),
            )
            LiveBrowseOrder.A_TO_Z -> filtered.sortedWith(
                compareBy<LiveChannelItem> { it.displayName.lowercase(Locale.ROOT) }
                    .thenBy(LiveChannelItem::providerOrder),
            )
            LiveBrowseOrder.Z_TO_A -> filtered.sortedWith(
                compareByDescending<LiveChannelItem> { it.displayName.lowercase(Locale.ROOT) }
                    .thenBy(LiveChannelItem::providerOrder),
            )
            LiveBrowseOrder.CATEGORY -> filtered.sortedWith(
                compareBy<LiveChannelItem> { it.categoryName.orEmpty().lowercase(Locale.ROOT) }
                    .thenBy(LiveChannelItem::providerOrder),
            )
        }
    }

    internal fun filterTransient(
        navigationChannels: List<LiveChannelItem>,
        query: LiveBrowseQuery,
    ): List<LiveChannelItem> {
        val normalizedSearch = query.searchTerm.trim().lowercase(Locale.ROOT)
        if (query.categoryKey == null && normalizedSearch.isEmpty()) {
            return navigationChannels
        }
        return navigationChannels.asSequence()
            .filter { item -> query.categoryKey == null || item.categoryKey == query.categoryKey }
            .filter { item -> matchesSearch(item, normalizedSearch) }
            .toList()
    }

    private fun matchesSearch(
        record: LiveChannelRecord,
        normalizedSearch: String,
    ): Boolean {
        if (normalizedSearch.isEmpty()) return true
        return displayName(record).lowercase(Locale.ROOT).contains(normalizedSearch) ||
            record.providerName.lowercase(Locale.ROOT).contains(normalizedSearch) ||
            record.categoryName.orEmpty().lowercase(Locale.ROOT).contains(normalizedSearch)
    }

    private fun matchesSearch(
        item: LiveChannelItem,
        normalizedSearch: String,
    ): Boolean {
        if (normalizedSearch.isEmpty()) return true
        return item.displayName.lowercase(Locale.ROOT).contains(normalizedSearch) ||
            item.providerName.lowercase(Locale.ROOT).contains(normalizedSearch) ||
            item.categoryName.orEmpty().lowercase(Locale.ROOT).contains(normalizedSearch)
    }

    private fun isEffectivelyHidden(
        record: LiveChannelRecord,
        hiddenCategoryKeys: Set<String>,
    ): Boolean = record.hiddenAtEpochMillis != null ||
        record.providerCategoryKey in hiddenCategoryKeys

    private fun displayName(record: LiveChannelRecord): String =
        record.localDisplayName?.takeIf(String::isNotBlank)
            ?: record.tvgName?.takeIf(String::isNotBlank)
            ?: record.providerName

    private fun toItem(
        record: LiveChannelRecord,
        customGroupIds: Set<String>,
        hiddenCategoryKeys: Set<String>,
    ): LiveChannelItem = LiveChannelItem(
        channelId = record.channelId,
        sourceId = record.sourceId,
        categoryKey = record.providerCategoryKey,
        categoryName = record.categoryName,
        providerName = record.providerName,
        localDisplayName = record.localDisplayName,
        displayName = displayName(record),
        logoRef = record.logoOverrideRef ?: record.logoRef,
        hasLogoOverride = record.logoOverrideRef != null,
        providerOrder = record.providerOrder,
        manualOrder = record.manualOrder,
        favoriteOrder = record.favoriteOrder,
        isFavorite = record.favoriteOrder != null,
        isHidden = isEffectivelyHidden(record, hiddenCategoryKeys),
        availability = record.availability,
        recentAtEpochMillis = record.recentAtEpochMillis,
        customGroupIds = customGroupIds,
    )
}
