package app.ownplay.mobile.feature.live.domain

import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.Flow

/**
 * Retained only for backup/schema compatibility with older v2/v3 data.
 * Runtime Live browsing is provider-only and does not expose mode switching.
 */
enum class LiveOrganizationMode {
    PROVIDER,
    OWNPLAY,
}

object ProviderLiveOrganizationContract {
    const val UNCATEGORIZED_CATEGORY_ID = "__provider_uncategorized__"
    const val UNCATEGORIZED_DISPLAY_NAME = "Uncategorized"
}

data class ProviderLiveCategory(
    val categoryId: String,
    val displayName: String,
    val providerOrder: Int,
)

data class LiveOrganizationChannel(
    val channelId: String,
    val name: String,
    val tvgName: String?,
    val providerCategoryId: String?,
    val providerOrder: Int,
    val logoUrl: String? = null,
    val localName: String? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
)

data class ProviderLiveCatalogSnapshot(
    val categories: List<ProviderLiveCategory>,
    val channels: List<LiveOrganizationChannel>,
)

data class ProviderLiveManagementCategory(
    val categoryId: String,
    val displayName: String,
    val providerOrder: Int,
    val hidden: Boolean,
    val manualOrder: Int?,
)

data class ProviderLiveManagementChannel(
    val channelId: String,
    val categoryId: String,
    val name: String,
    val tvgName: String?,
    val logoUrl: String?,
    val providerOrder: Int,
    val hidden: Boolean,
    val manualOrder: Int?,
    val favorite: Boolean = false,
    val localName: String? = null,
)

data class ProviderLiveManagementSnapshot(
    val categories: List<ProviderLiveManagementCategory>,
    val channels: List<ProviderLiveManagementChannel>,
)

interface LiveOrganizationRepository {
    fun observeProviderCatalog(sourceId: SourceId): Flow<ProviderLiveCatalogSnapshot>

    fun observeProviderManagement(sourceId: SourceId): Flow<ProviderLiveManagementSnapshot>

    fun observeFavoriteChannelIds(sourceId: SourceId): Flow<Set<String>>

    suspend fun setFavorite(sourceId: SourceId, channelId: String, favorite: Boolean): Boolean

    suspend fun setChannelHidden(sourceId: SourceId, channelId: String, hidden: Boolean): Boolean

    suspend fun setProviderCategoryHidden(
        sourceId: SourceId,
        categoryId: String,
        hidden: Boolean,
    ): Boolean

    suspend fun setProviderCategoryOrder(
        sourceId: SourceId,
        orderedCategoryIds: List<String>,
    ): Boolean

    suspend fun resetProviderCategoryOrder(sourceId: SourceId): Boolean

    suspend fun showAllProviderCategories(sourceId: SourceId): Boolean

    suspend fun setProviderChannelHidden(
        sourceId: SourceId,
        categoryId: String,
        channelId: String,
        hidden: Boolean,
    ): Boolean

    suspend fun setProviderChannelOrder(
        sourceId: SourceId,
        categoryId: String,
        orderedChannelIds: List<String>,
    ): Boolean

    suspend fun resetProviderChannelOrder(
        sourceId: SourceId,
        categoryId: String,
    ): Boolean
}
