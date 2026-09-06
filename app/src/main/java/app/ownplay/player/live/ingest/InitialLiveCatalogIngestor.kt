package app.ownplay.player.live.ingest

import androidx.room.withTransaction
import app.ownplay.player.persistence.ChannelAvailability
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.ProviderCategoryEntity
import app.ownplay.player.persistence.ProviderChannelEntity
import app.ownplay.player.persistence.reconcile.CatalogGenerationClock
import app.ownplay.player.persistence.reconcile.ChannelReconciler
import app.ownplay.player.persistence.reconcile.ExistingChannelIdentity
import app.ownplay.player.persistence.reconcile.ReconciliationResult
import app.ownplay.player.persistence.secure.SensitiveValueRef
import app.ownplay.player.persistence.secure.SensitiveValueStore
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException

interface LiveCatalogPersistence {
    suspend fun existingChannels(sourceId: String): List<ProviderChannelEntity>

    suspend fun applyInitialCatalog(
        categories: List<ProviderCategoryEntity>,
        channels: List<ProviderChannelEntity>,
    )
}

class RoomLiveCatalogPersistence(
    private val database: OwnPlayDatabase,
) : LiveCatalogPersistence {
    override suspend fun existingChannels(sourceId: String): List<ProviderChannelEntity> =
        database.providerCatalogDao().channelsForSource(sourceId)

    override suspend fun applyInitialCatalog(
        categories: List<ProviderCategoryEntity>,
        channels: List<ProviderChannelEntity>,
    ) {
        database.withTransaction {
            val sourceId = channels.firstOrNull()?.sourceId ?: categories.firstOrNull()?.sourceId
            if (sourceId != null) {
                database.providerCatalogDao().deleteCategoriesForSource(sourceId)
            }
            database.providerCatalogDao().upsertCategories(categories)
            database.providerCatalogDao().upsertChannels(channels)
            val firstChannel = channels.firstOrNull()
            if (firstChannel != null) {
                database.providerCatalogDao().markChannelsMissingFromGeneration(
                    sourceId = firstChannel.sourceId,
                    generation = firstChannel.lastSeenGeneration,
                )
            }
        }
    }
}

sealed interface InitialLiveCatalogIngestResult {
    data class Success(
        val categoryCount: Int,
        val channelCount: Int,
    ) : InitialLiveCatalogIngestResult

    data object DuplicateCategoryKey : InitialLiveCatalogIngestResult
    data object DuplicateChannelKey : InitialLiveCatalogIngestResult
    data object InvalidInput : InitialLiveCatalogIngestResult
    data object SecureStorageFailure : InitialLiveCatalogIngestResult
    data object PersistenceFailure : InitialLiveCatalogIngestResult
}

class InitialLiveCatalogIngestor(
    private val persistence: LiveCatalogPersistence,
    private val sensitiveValueStore: SensitiveValueStore,
) {
    suspend fun ingest(
        sourceId: String,
        generation: Long,
        catalog: IncomingLiveCatalog,
    ): InitialLiveCatalogIngestResult {
        if (
            sourceId.isBlank() ||
            generation < 0 ||
            catalog.categories.any { it.providerKey.isBlank() } ||
            catalog.channels.any { it.providerKey.isBlank() || it.locatorValue.isBlank() }
        ) {
            return InitialLiveCatalogIngestResult.InvalidInput
        }
        if (hasDuplicateCategoryKeys(catalog.categories)) {
            return InitialLiveCatalogIngestResult.DuplicateCategoryKey
        }

        val existing = try {
            persistence.existingChannels(sourceId)
        } catch (error: Exception) {
            error.rethrowCancellation()
            return InitialLiveCatalogIngestResult.PersistenceFailure
        }
        val effectiveGeneration = CatalogGenerationClock(
            initialGeneration = existing.maxOfOrNull(ProviderChannelEntity::lastSeenGeneration)
                ?: Long.MIN_VALUE,
        ).next(generation)

        val reconciliation = ChannelReconciler.plan(
            existing = existing.map { channel ->
                ExistingChannelIdentity(
                    channelId = channel.channelId,
                    providerKey = channel.providerKey,
                )
            },
            incomingProviderKeys = catalog.channels.map(IncomingLiveChannel::providerKey),
        )
        val reconciliationPlan = when (reconciliation) {
            is ReconciliationResult.Success -> reconciliation.plan
            is ReconciliationResult.DuplicateExistingProviderKey,
            is ReconciliationResult.DuplicateIncomingProviderKey,
            -> return InitialLiveCatalogIngestResult.DuplicateChannelKey
        }

        val sensitiveValues = buildList {
            catalog.channels.forEach { incoming ->
                add(incoming.locatorValue)
                incoming.logoValue?.let(::add)
            }
        }
        val allocatedRefs = try {
            sensitiveValueStore.putAll(sensitiveValues)
        } catch (error: Exception) {
            error.rethrowCancellation()
            return InitialLiveCatalogIngestResult.SecureStorageFailure
        }
        val refIterator = allocatedRefs.iterator()
        val existingByChannelId = existing.associateBy(ProviderChannelEntity::channelId)
        val oldRefsToDelete = linkedSetOf<SensitiveValueRef>()

        val channelEntities = try {
            catalog.channels.map { incoming ->
                val channelId = reconciliationPlan.matchedChannelIdsByProviderKey[incoming.providerKey]
                    ?: stableLocalId("channel", sourceId, incoming.providerKey)
                val previous = existingByChannelId[channelId]

                val streamRef = refIterator.next()
                val logoRef = incoming.logoValue?.let { refIterator.next() }

                previous?.streamLocatorRef?.let { oldRef ->
                    if (oldRef != streamRef.value) oldRefsToDelete += SensitiveValueRef(oldRef)
                }
                previous?.logoRef?.let { oldRef ->
                    if (oldRef != logoRef?.value) oldRefsToDelete += SensitiveValueRef(oldRef)
                }

                ProviderChannelEntity(
                    channelId = channelId,
                    sourceId = sourceId,
                    providerKey = incoming.providerKey,
                    providerStreamId = incoming.providerStreamId,
                    providerCategoryKey = incoming.providerCategoryKey,
                    providerName = incoming.providerName,
                    tvgId = incoming.tvgId,
                    tvgName = incoming.tvgName,
                    logoRef = logoRef?.value,
                    streamLocatorRef = streamRef.value,
                    providerOrder = incoming.providerOrder,
                    availability = ChannelAvailability.AVAILABLE,
                    lastSeenGeneration = effectiveGeneration,
                )
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            cleanup(allocatedRefs)
            return InitialLiveCatalogIngestResult.SecureStorageFailure
        }

        val categoryEntities = catalog.categories.map { incoming ->
            ProviderCategoryEntity(
                categoryId = stableLocalId("category", sourceId, incoming.providerKey),
                sourceId = sourceId,
                providerCategoryKey = incoming.providerKey,
                name = incoming.name,
                parentProviderKey = incoming.parentProviderKey,
                providerOrder = incoming.providerOrder,
            )
        }

        try {
            persistence.applyInitialCatalog(categoryEntities, channelEntities)
        } catch (error: Exception) {
            error.rethrowCancellation()
            cleanup(allocatedRefs)
            return InitialLiveCatalogIngestResult.PersistenceFailure
        }

        cleanup(oldRefsToDelete)
        return InitialLiveCatalogIngestResult.Success(
            categoryCount = categoryEntities.size,
            channelCount = channelEntities.size,
        )
    }

    private fun cleanup(refs: Iterable<SensitiveValueRef>) {
        val batch = refs.toList()
        if (batch.isEmpty()) return
        runCatching { sensitiveValueStore.deleteAll(batch) }
    }

    private fun hasDuplicateCategoryKeys(categories: List<IncomingLiveCategory>): Boolean {
        val seen = hashSetOf<String>()
        return categories.any { category -> !seen.add(category.providerKey) }
    }

    private fun stableLocalId(
        kind: String,
        sourceId: String,
        providerKey: String,
    ): String = UUID.nameUUIDFromBytes(
        "$kind|$sourceId|$providerKey".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}
