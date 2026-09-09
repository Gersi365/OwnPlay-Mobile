package app.ownplay.mobile.data.repository

import androidx.room.withTransaction
import app.ownplay.mobile.data.db.OwnPlayV2Database
import app.ownplay.mobile.data.db.V2CategoryEntity
import app.ownplay.mobile.data.db.V2MediaItemEntity
import app.ownplay.mobile.data.db.V2SourceEntity
import app.ownplay.mobile.data.preferences.SourceSelectionStore
import app.ownplay.mobile.sources.CatalogType
import app.ownplay.mobile.sources.CredentialState
import app.ownplay.mobile.sources.ProviderItem
import app.ownplay.mobile.sources.RefreshResult
import app.ownplay.mobile.sources.SourceDescriptor
import app.ownplay.mobile.sources.SourceDraft
import app.ownplay.mobile.sources.SourceKind
import app.ownplay.mobile.sources.SourceRepository
import app.ownplay.mobile.sources.SourceSnapshot
import app.ownplay.mobile.sources.XtreamCredentials
import app.ownplay.mobile.sources.network.M3uSourceClient
import app.ownplay.mobile.sources.network.XtreamSourceClient
import app.ownplay.mobile.sources.security.SourceCredentialStore
import app.ownplay.mobile.sources.stableCategoryId
import app.ownplay.mobile.sources.stableProviderItemId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSourceRepository(
    private val database: OwnPlayV2Database,
    private val selectionStore: SourceSelectionStore,
    private val credentialStore: SourceCredentialStore,
    private val m3uClient: M3uSourceClient,
    private val xtreamClient: XtreamSourceClient,
    private val now: () -> Long = System::currentTimeMillis,
) : SourceRepository {
    override fun observeSources(): Flow<List<SourceDescriptor>> =
        database.sourceDao().observeSources().map { entities -> entities.map(V2SourceEntity::toDomain) }

    override fun observeCatalog(
        sourceId: String,
        catalogType: CatalogType,
    ): Flow<List<ProviderItem>> = database.catalogDao()
        .observeAvailableItems(sourceId, catalogType.name)
        .map { items -> items.map(V2MediaItemEntity::toDomain) }

    override fun observeActiveSourceId(): Flow<String?> = selectionStore.observeActiveSourceId()

    override suspend fun addSource(
        draft: SourceDraft,
        credentials: XtreamCredentials?,
    ): SourceDescriptor {
        val cleanName = draft.name.trim()
        val cleanLocator = draft.locator.trim().trimEnd('/')
        require(cleanName.isNotBlank()) { "Source name is required" }
        require(cleanLocator.isNotBlank()) { "Source locator is required" }
        if (draft.kind == SourceKind.XTREAM) requireNotNull(credentials)
        val sourceId = UUID.randomUUID().toString()
        val timestamp = now()
        val entity = V2SourceEntity(
            sourceId = sourceId,
            name = cleanName,
            kind = draft.kind.name,
            locator = cleanLocator,
            enabled = draft.enabled,
            credentialState = if (draft.kind == SourceKind.XTREAM) {
                CredentialState.AVAILABLE.name
            } else {
                CredentialState.NOT_REQUIRED.name
            },
            createdAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
            refreshGeneration = 0,
            lastRefreshAtEpochMillis = null,
            lastRefreshError = null,
        )
        if (credentials != null) credentialStore.putXtream(sourceId, credentials)
        try {
            database.sourceDao().upsert(entity)
        } catch (error: Throwable) {
            if (credentials != null) credentialStore.remove(sourceId)
            throw error
        }
        return entity.toDomain()
    }

    override suspend fun updateSource(
        source: SourceDescriptor,
        credentials: XtreamCredentials?,
    ) {
        val existing = requireNotNull(database.sourceDao().getSource(source.sourceId))
        val timestamp = now()
        val credentialState = when (source.kind) {
            SourceKind.M3U -> {
                credentialStore.remove(source.sourceId)
                CredentialState.NOT_REQUIRED
            }
            SourceKind.XTREAM -> {
                if (credentials != null) {
                    credentialStore.putXtream(source.sourceId, credentials)
                    CredentialState.AVAILABLE
                } else {
                    CredentialState.valueOf(existing.credentialState)
                }
            }
        }
        database.sourceDao().upsert(
            existing.copy(
                name = source.name.trim(),
                kind = source.kind.name,
                locator = source.locator.trim().trimEnd('/'),
                enabled = source.enabled,
                credentialState = credentialState.name,
                updatedAtEpochMillis = timestamp,
            ),
        )
    }

    override suspend fun removeSource(sourceId: String) {
        database.sourceDao().delete(sourceId)
        credentialStore.remove(sourceId)
        val remaining = database.sourceDao().getSources().filter { it.enabled }.map { it.sourceId }
        val current = selectionStore.observeActiveSourceId()
        if (sourceId !in remaining) {
            selectionStore.setActiveSourceId(remaining.firstOrNull())
        }
    }

    override suspend fun selectSource(sourceId: String?) {
        if (sourceId != null) {
            val source = requireNotNull(database.sourceDao().getSource(sourceId))
            require(source.enabled) { "Disabled source cannot be selected" }
        }
        selectionStore.setActiveSourceId(sourceId)
    }

    override suspend fun refreshSource(sourceId: String): RefreshResult {
        val source = requireNotNull(database.sourceDao().getSource(sourceId))
        val descriptor = source.toDomain()
        val credentials = when (descriptor.kind) {
            SourceKind.XTREAM -> credentialStore.getXtream(sourceId)
            SourceKind.M3U -> null
        }
        if (descriptor.kind == SourceKind.XTREAM && credentials == null) {
            return RefreshResult.Failure("XTREAM_CREDENTIALS_REQUIRED")
        }
        return try {
            val snapshot = when (descriptor.kind) {
                SourceKind.XTREAM -> xtreamClient.fetch(descriptor, credentials)
                SourceKind.M3U -> m3uClient.fetch(descriptor, null)
            }
            val generation = source.refreshGeneration + 1
            val reconciled = SourceRefreshReconciler.reconcile(sourceId, generation, snapshot)
            database.withTransaction {
                database.catalogDao().upsertCategories(reconciled.categories)
                database.catalogDao().upsertItems(reconciled.items)
                database.catalogDao().markUnseenCategoriesUnavailable(sourceId, generation)
                database.catalogDao().markUnseenItemsUnavailable(sourceId, generation)
                database.sourceDao().recordRefreshSuccess(sourceId, generation, now())
            }
            RefreshResult.Success(generation = generation, itemCount = reconciled.items.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val reason = error::class.simpleName ?: "SOURCE_REFRESH_FAILED"
            database.sourceDao().recordRefreshFailure(sourceId, reason, now())
            RefreshResult.Failure(reason)
        }
    }
}

internal data class ReconciledSnapshot(
    val categories: List<V2CategoryEntity>,
    val items: List<V2MediaItemEntity>,
)

internal object SourceRefreshReconciler {
    fun reconcile(
        sourceId: String,
        generation: Long,
        snapshot: SourceSnapshot,
    ): ReconciledSnapshot = ReconciledSnapshot(
        categories = snapshot.categories.map { category ->
            V2CategoryEntity(
                categoryId = stableCategoryId(sourceId, category.catalogType, category.providerKey),
                sourceId = sourceId,
                catalogType = category.catalogType.name,
                providerKey = category.providerKey,
                name = category.name,
                providerOrder = category.providerOrder,
                lastSeenGeneration = generation,
                available = true,
            )
        },
        items = snapshot.items.map { item ->
            V2MediaItemEntity(
                itemId = stableProviderItemId(sourceId, item.catalogType, item.providerKey),
                sourceId = sourceId,
                catalogType = item.catalogType.name,
                providerKey = item.providerKey,
                categoryKey = item.categoryKey,
                name = item.name,
                artworkUrl = item.artworkUrl,
                streamLocator = item.streamLocator,
                containerExtension = item.containerExtension,
                rating = item.rating,
                providerOrder = item.providerOrder,
                lastSeenGeneration = generation,
                available = true,
            )
        },
    )
}

private fun V2SourceEntity.toDomain(): SourceDescriptor = SourceDescriptor(
    sourceId = sourceId,
    name = name,
    kind = SourceKind.valueOf(kind),
    locator = locator,
    enabled = enabled,
    credentialState = CredentialState.valueOf(credentialState),
)

private fun V2MediaItemEntity.toDomain(): ProviderItem = ProviderItem(
    catalogType = CatalogType.valueOf(catalogType),
    providerKey = providerKey,
    categoryKey = categoryKey,
    name = name,
    artworkUrl = artworkUrl,
    streamLocator = streamLocator,
    containerExtension = containerExtension,
    rating = rating,
    providerOrder = providerOrder,
)
