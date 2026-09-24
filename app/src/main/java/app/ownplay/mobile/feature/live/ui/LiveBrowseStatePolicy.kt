package app.ownplay.mobile.feature.live.ui

import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import app.ownplay.mobile.feature.live.domain.ProviderLiveCatalogSnapshot
import app.ownplay.mobile.feature.live.domain.ProviderLiveOrganizationContract

data class ProviderLiveCategoryOption(
    val categoryId: String,
    val displayName: String,
)

object LiveBrowseStatePolicy {
    const val PROVIDER_UNCATEGORIZED_ID = ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
    const val PROVIDER_UNCATEGORIZED_NAME = ProviderLiveOrganizationContract.UNCATEGORIZED_DISPLAY_NAME

    fun providerCategoryOptions(catalog: ProviderLiveCatalogSnapshot): List<ProviderLiveCategoryOption> =
        catalog.categories.map { category ->
            ProviderLiveCategoryOption(
                categoryId = category.categoryId,
                displayName = category.displayName,
            )
        }

    fun selectedProviderCategoryId(
        requestedCategoryId: String?,
        catalog: ProviderLiveCatalogSnapshot,
    ): String? {
        val options = providerCategoryOptions(catalog)
        return requestedCategoryId
            ?.takeIf { requested -> options.any { it.categoryId == requested } }
            ?: options.firstOrNull()?.categoryId
    }

    fun searchChannelIds(
        channels: List<LiveOrganizationChannel>,
        query: String,
        favoritesOnly: Boolean,
        favoriteChannelIds: Set<String>,
        candidateChannelIds: List<String>? = null,
    ): List<String> {
        val channelsById = channels.associateBy { it.channelId }
        val orderedChannels = candidateChannelIds
            ?.mapNotNull(channelsById::get)
            ?: channels
        val term = query.trim()
        if (term.isEmpty()) {
            return orderedChannels
                .asSequence()
                .filter { !favoritesOnly || it.channelId in favoriteChannelIds }
                .map { it.channelId }
                .toList()
        }
        return orderedChannels
            .asSequence()
            .filter { !favoritesOnly || it.channelId in favoriteChannelIds }
            .filter { channel ->
                sequenceOf(channel.name, channel.tvgName, channel.localName)
                    .filterNotNull()
                    .any { value -> value.contains(term, ignoreCase = true) }
            }
            .map { it.channelId }
            .toList()
    }

    fun visibleProviderChannelIds(
        catalog: ProviderLiveCatalogSnapshot,
        categoryId: String?,
        favoritesOnly: Boolean,
        favoriteChannelIds: Set<String>,
    ): List<String> {
        val selectedCategoryId = categoryId ?: return emptyList()
        return catalog.channels
            .asSequence()
            .filter { channel ->
                if (selectedCategoryId == PROVIDER_UNCATEGORIZED_ID) {
                    channel.providerCategoryId == null ||
                        channel.providerCategoryId == PROVIDER_UNCATEGORIZED_ID
                } else {
                    channel.providerCategoryId == selectedCategoryId
                }
            }
            .filter { channel -> !favoritesOnly || channel.channelId in favoriteChannelIds }
            .map { it.channelId }
            .toList()
    }
}