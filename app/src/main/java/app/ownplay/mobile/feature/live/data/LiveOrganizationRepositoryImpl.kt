package app.ownplay.mobile.feature.live.data

import androidx.room.withTransaction
import app.ownplay.mobile.data.db.ChannelPersonalizationEntity
import app.ownplay.mobile.data.db.LiveCategoryScopePersonalizationEntity
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveChannelMembershipPersonalizationEntity
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import app.ownplay.mobile.feature.live.domain.LiveOrganizationMode
import app.ownplay.mobile.feature.live.domain.LiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.ProviderLiveCatalogSnapshot
import app.ownplay.mobile.feature.live.domain.ProviderLiveCategory
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementCategory
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementChannel
import app.ownplay.mobile.feature.live.domain.ProviderLiveManagementSnapshot
import app.ownplay.mobile.feature.live.domain.ProviderLiveOrganizationContract
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomLiveOrganizationRepository(
    private val database: OwnPlayDatabase,
    private val dao: LiveOrganizationDao,
) : LiveOrganizationRepository {
    override fun observeProviderCatalog(sourceId: SourceId): Flow<ProviderLiveCatalogSnapshot> =
        observeProviderManagement(sourceId)
            .map(::visibleProviderCatalog)
            .flowOn(Dispatchers.Default)

    override fun observeProviderManagement(
        sourceId: SourceId,
    ): Flow<ProviderLiveManagementSnapshot> =
        combine(
            dao.observeProviderLiveCategories(sourceId.value),
            dao.observeLiveChannels(sourceId.value),
            dao.observeProviderCategoryPersonalization(sourceId.value),
            dao.observeProviderChannelPersonalization(sourceId.value),
            dao.observeChannelPersonalization(sourceId.value),
        ) { categoryRows, channelRows, categoryPersonalization, channelPersonalization, globalPersonalization ->
            buildProviderManagementSnapshot(
                categoryRows = categoryRows,
                channelRows = channelRows,
                categoryPersonalization = categoryPersonalization,
                channelPersonalization = channelPersonalization,
                globalPersonalization = globalPersonalization,
            )
        }.flowOn(Dispatchers.Default)

    override fun observeFavoriteChannelIds(sourceId: SourceId): Flow<Set<String>> =
        dao.observeFavoriteChannelIds(sourceId.value).map { rows -> rows.toSet() }

    override suspend fun setFavorite(
        sourceId: SourceId,
        channelId: String,
        favorite: Boolean,
    ): Boolean = try {
        database.withTransaction {
            val channel = dao.getAvailableChannel(sourceId.value, channelId)
                ?: return@withTransaction false
            val providerCategoryId =
                channel.categoryKey ?: ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
            val providerState = dao.getProviderChannelPersonalization(
                sourceId.value,
                providerCategoryId,
                channel.channelId,
            )
            val current = dao.getChannelPersonalization(channel.channelId)
            val effectiveHidden = current?.hidden == true || providerState?.hidden == true
            dao.upsertChannelPersonalization(
                (current ?: ChannelPersonalizationEntity(channelId = channel.channelId)).copy(
                    favorite = favorite,
                    hidden = effectiveHidden,
                ),
            )
            providerState?.let { previous ->
                dao.upsertProviderChannelPersonalization(
                    previous.copy(hidden = effectiveHidden),
                )
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun setChannelHidden(
        sourceId: SourceId,
        channelId: String,
        hidden: Boolean,
    ): Boolean = try {
        database.withTransaction {
            val channel = dao.getAvailableChannel(sourceId.value, channelId)
                ?: return@withTransaction false
            updateGlobalChannelVisibility(
                sourceId = sourceId,
                channel = channel,
                hidden = hidden,
            )
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun setProviderCategoryHidden(
        sourceId: SourceId,
        categoryId: String,
        hidden: Boolean,
    ): Boolean = try {
        database.withTransaction {
            val categoryIds = providerCategoryIds(sourceId)
            if (categoryId !in categoryIds) return@withTransaction false
            val current = dao.getProviderCategoryPersonalization(sourceId.value, categoryId)
            dao.upsertProviderCategoryPersonalization(
                (current ?: LiveCategoryScopePersonalizationEntity(
                    sourceId = sourceId.value,
                    organizationMode = LiveOrganizationMode.PROVIDER.name,
                    categoryId = categoryId,
                )).copy(hidden = hidden),
            )
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun setProviderCategoryOrder(
        sourceId: SourceId,
        orderedCategoryIds: List<String>,
    ): Boolean = try {
        database.withTransaction {
            val ordered = orderedCategoryIds.filter(String::isNotBlank).distinct()
            val available = providerCategoryIds(sourceId)
            if (ordered.size != available.size || ordered.toSet() != available.toSet()) {
                return@withTransaction false
            }
            ordered.forEachIndexed { index, categoryId ->
                val current = dao.getProviderCategoryPersonalization(sourceId.value, categoryId)
                dao.upsertProviderCategoryPersonalization(
                    (current ?: LiveCategoryScopePersonalizationEntity(
                        sourceId = sourceId.value,
                        organizationMode = LiveOrganizationMode.PROVIDER.name,
                        categoryId = categoryId,
                    )).copy(manualOrder = index),
                )
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun resetProviderCategoryOrder(sourceId: SourceId): Boolean = try {
        database.withTransaction {
            val available = providerCategoryIds(sourceId)
            if (available.isEmpty()) return@withTransaction false
            available.forEach { categoryId ->
                val current = dao.getProviderCategoryPersonalization(sourceId.value, categoryId)
                if (current?.manualOrder != null) {
                    dao.upsertProviderCategoryPersonalization(current.copy(manualOrder = null))
                }
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun showAllProviderCategories(sourceId: SourceId): Boolean = try {
        database.withTransaction {
            val available = providerCategoryIds(sourceId)
            if (available.isEmpty()) return@withTransaction false
            available.forEach { categoryId ->
                val current = dao.getProviderCategoryPersonalization(sourceId.value, categoryId)
                if (current?.hidden == true) {
                    dao.upsertProviderCategoryPersonalization(current.copy(hidden = false))
                }
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun setProviderChannelHidden(
        sourceId: SourceId,
        categoryId: String,
        channelId: String,
        hidden: Boolean,
    ): Boolean = try {
        database.withTransaction {
            val channelIds = providerChannelIds(sourceId, categoryId)
            if (channelId !in channelIds) return@withTransaction false
            val channel = dao.getAvailableChannel(sourceId.value, channelId)
                ?: return@withTransaction false
            updateGlobalChannelVisibility(
                sourceId = sourceId,
                channel = channel,
                hidden = hidden,
                providerCategoryId = categoryId,
            )
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun setProviderChannelOrder(
        sourceId: SourceId,
        categoryId: String,
        orderedChannelIds: List<String>,
    ): Boolean = try {
        database.withTransaction {
            val ordered = orderedChannelIds.filter(String::isNotBlank).distinct()
            val available = providerChannelIds(sourceId, categoryId)
            if (ordered.size != available.size || ordered.toSet() != available.toSet()) {
                return@withTransaction false
            }
            ordered.forEachIndexed { index, channelId ->
                val channel = dao.getAvailableChannel(sourceId.value, channelId)
                    ?: return@withTransaction false
                val providerState = dao.getProviderChannelPersonalization(
                    sourceId.value,
                    categoryId,
                    channelId,
                )
                val current = dao.getChannelPersonalization(channelId)
                val effectiveHidden = current?.hidden == true || providerState?.hidden == true
                dao.upsertProviderChannelPersonalization(
                    (providerState ?: LiveChannelMembershipPersonalizationEntity(
                        sourceId = sourceId.value,
                        organizationMode = LiveOrganizationMode.PROVIDER.name,
                        categoryId = categoryId,
                        channelId = channel.channelId,
                    )).copy(
                        hidden = effectiveHidden,
                        manualOrder = index,
                    ),
                )
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    override suspend fun resetProviderChannelOrder(
        sourceId: SourceId,
        categoryId: String,
    ): Boolean = try {
        database.withTransaction {
            val available = providerChannelIds(sourceId, categoryId)
            if (available.isEmpty()) return@withTransaction false
            available.forEach { channelId ->
                val providerState = dao.getProviderChannelPersonalization(
                    sourceId.value,
                    categoryId,
                    channelId,
                )
                val current = dao.getChannelPersonalization(channelId)
                val effectiveHidden = current?.hidden == true || providerState?.hidden == true
                if (providerState?.manualOrder != null) {
                    dao.upsertProviderChannelPersonalization(
                        providerState.copy(
                            hidden = effectiveHidden,
                            manualOrder = null,
                        ),
                    )
                }
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun buildProviderManagementSnapshot(
        categoryRows: List<ProviderCategoryEntity>,
        channelRows: List<LiveChannelEntity>,
        categoryPersonalization: List<LiveCategoryScopePersonalizationEntity>,
        channelPersonalization: List<LiveChannelMembershipPersonalizationEntity>,
        globalPersonalization: List<ChannelPersonalizationEntity>,
    ): ProviderLiveManagementSnapshot {
        val categoryPersonalizationById = categoryPersonalization.associateBy { it.categoryId }
        val globalPersonalizationById = globalPersonalization.associateBy { it.channelId }
        val categories = buildList {
            categoryRows.forEach { row ->
                val personalization = categoryPersonalizationById[row.categoryKey]
                add(
                    ProviderLiveManagementCategory(
                        categoryId = row.categoryKey,
                        displayName = row.name,
                        providerOrder = row.providerOrder,
                        hidden = personalization?.hidden ?: false,
                        manualOrder = personalization?.manualOrder,
                    ),
                )
            }
            if (channelRows.any { it.categoryKey == null }) {
                val uncategorizedId = ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
                val personalization = categoryPersonalizationById[uncategorizedId]
                add(
                    ProviderLiveManagementCategory(
                        categoryId = uncategorizedId,
                        displayName = ProviderLiveOrganizationContract.UNCATEGORIZED_DISPLAY_NAME,
                        providerOrder = Int.MAX_VALUE,
                        hidden = personalization?.hidden ?: false,
                        manualOrder = personalization?.manualOrder,
                    ),
                )
            }
        }.withIndex()
            .sortedWith(
                compareBy<IndexedValue<ProviderLiveManagementCategory>> {
                    it.value.manualOrder ?: Int.MAX_VALUE
                }
                    .thenBy { it.value.providerOrder }
                    .thenBy { it.index },
            )
            .map(IndexedValue<ProviderLiveManagementCategory>::value)

        val categoryRank = categories.mapIndexed { index, category -> category.categoryId to index }.toMap()
        val channelPersonalizationByKey = channelPersonalization.associateBy { row ->
            row.categoryId to row.channelId
        }
        val channels = channelRows.map { row ->
            val categoryId = row.categoryKey ?: ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
            val personalization = channelPersonalizationByKey[categoryId to row.channelId]
            val global = globalPersonalizationById[row.channelId]
            ProviderLiveManagementChannel(
                channelId = row.channelId,
                categoryId = categoryId,
                name = row.name,
                tvgName = row.tvgName,
                logoUrl = global?.localLogo?.trim()?.takeIf(String::isNotEmpty) ?: row.logoUrl,
                providerOrder = row.providerOrder,
                hidden = global?.hidden == true || personalization?.hidden == true,
                manualOrder = personalization?.manualOrder,
                favorite = global?.favorite ?: false,
                localName = global?.localName?.trim()?.takeIf(String::isNotEmpty),
            )
        }.withIndex()
            .sortedWith(
                compareBy<IndexedValue<ProviderLiveManagementChannel>> {
                    categoryRank[it.value.categoryId] ?: Int.MAX_VALUE
                }
                    .thenBy { it.value.manualOrder ?: Int.MAX_VALUE }
                    .thenBy { it.value.providerOrder }
                    .thenBy { it.index },
            )
            .map(IndexedValue<ProviderLiveManagementChannel>::value)

        return ProviderLiveManagementSnapshot(
            categories = categories,
            channels = channels,
        )
    }

    private fun visibleProviderCatalog(
        management: ProviderLiveManagementSnapshot,
    ): ProviderLiveCatalogSnapshot {
        val uncategorizedId = ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID
        val uncategorizedVisible = management.categories
            .firstOrNull { it.categoryId == uncategorizedId }
            ?.hidden != true
        val visibleCategories = management.categories
            .filterNot { category ->
                category.hidden || category.categoryId == uncategorizedId
            }
        val visibleCategoryIds = visibleCategories.mapTo(linkedSetOf()) { it.categoryId }
        return ProviderLiveCatalogSnapshot(
            categories = visibleCategories.map { category ->
                ProviderLiveCategory(
                    categoryId = category.categoryId,
                    displayName = category.displayName,
                    providerOrder = category.providerOrder,
                )
            },
            channels = management.channels
                .asSequence()
                .filter { channel ->
                    !channel.hidden && (
                        channel.categoryId in visibleCategoryIds ||
                            (channel.categoryId == uncategorizedId && uncategorizedVisible)
                        )
                }
                .map { channel ->
                    LiveOrganizationChannel(
                        channelId = channel.channelId,
                        name = channel.name,
                        tvgName = channel.tvgName,
                        providerCategoryId = channel.categoryId.takeUnless { it == uncategorizedId },
                        providerOrder = channel.providerOrder,
                        logoUrl = channel.logoUrl,
                        localName = channel.localName,
                        favorite = channel.favorite,
                    )
                }
                .toList(),
        )
    }

    private suspend fun providerCategoryIds(sourceId: SourceId): List<String> = buildList {
        addAll(dao.getProviderLiveCategoryIds(sourceId.value))
        if (dao.countProviderUncategorizedChannels(sourceId.value) > 0) {
            add(ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID)
        }
    }

    private suspend fun providerChannelIds(
        sourceId: SourceId,
        categoryId: String,
    ): List<String> = dao.getProviderChannelIds(
        sourceId = sourceId.value,
        categoryId = categoryId,
        uncategorizedCategoryId = ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID,
    )

    private suspend fun updateGlobalChannelVisibility(
        sourceId: SourceId,
        channel: LiveChannelEntity,
        hidden: Boolean,
        providerCategoryId: String =
            channel.categoryKey ?: ProviderLiveOrganizationContract.UNCATEGORIZED_CATEGORY_ID,
    ) {
        val providerState = dao.getProviderChannelPersonalization(
            sourceId.value,
            providerCategoryId,
            channel.channelId,
        )
        val current = dao.getChannelPersonalization(channel.channelId)
        dao.upsertChannelPersonalization(
            (current ?: ChannelPersonalizationEntity(channelId = channel.channelId)).copy(
                hidden = hidden,
            ),
        )
        dao.upsertProviderChannelPersonalization(
            (providerState ?: LiveChannelMembershipPersonalizationEntity(
                sourceId = sourceId.value,
                organizationMode = LiveOrganizationMode.PROVIDER.name,
                categoryId = providerCategoryId,
                channelId = channel.channelId,
            )).copy(hidden = hidden),
        )
    }


}