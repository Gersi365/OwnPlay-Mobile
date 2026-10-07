package app.ownplay.mobile.sources.data

import app.ownplay.mobile.data.db.RefreshStateDao
import app.ownplay.mobile.data.db.RefreshStateEntity
import app.ownplay.mobile.data.db.SourceDao
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.security.CredentialInputPolicy
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.SourceSecret
import app.ownplay.mobile.sources.domain.ConnectionValidation
import app.ownplay.mobile.sources.domain.SourceConnectionSecurityPolicy
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceInput
import app.ownplay.mobile.sources.domain.SourceMutationRejection
import app.ownplay.mobile.sources.domain.SourceMutationResult
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import app.ownplay.mobile.sources.domain.SourceReconnectInput
import app.ownplay.mobile.sources.domain.SourceRefreshResult
import app.ownplay.mobile.sources.domain.SourceRepository
import app.ownplay.mobile.sources.domain.SourceSummary
import app.ownplay.mobile.sources.domain.SourceType
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SourceRepositoryImpl(
    private val sourceDao: SourceDao,
    private val refreshStateDao: RefreshStateDao,
    private val activeSourceStore: ActiveSourceSelectionStore,
    private val credentialStore: CredentialStore,
    private val catalogLoader: SourceCatalogLoader,
    private val catalogRefreshStore: CatalogRefreshStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newSourceId: () -> SourceId = { SourceId(UUID.randomUUID().toString()) },
) : SourceRepository {
    private val refreshMutex = Mutex()
    private val stagedCatalogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stagedCatalogJobs = ConcurrentHashMap<SourceId, Job>()

    override fun observeSources(): Flow<List<SourceSummary>> = combine(
        sourceDao.observeAll(),
        refreshStateDao.observeAll(),
    ) { sources, refreshStates ->
        val refreshBySource = refreshStates.associateBy { it.sourceId }
        sources.map { source ->
            source.toSummary(refreshBySource[source.sourceId])
        }
    }

    override fun observeActiveSource(): Flow<SourceSummary?> = combine(
        observeSources(),
        activeSourceStore.selectedSourceId,
    ) { sources, selectedSourceId ->
        selectedSourceId?.let { selected ->
            sources.firstOrNull { source ->
                source.enabled && source.sourceId.value == selected
            }
        }
    }

    override suspend fun addSource(input: SourceInput): SourceMutationResult {
        val displayName = CredentialInputPolicy.normalizeDisplayName(input.displayName)
            ?: return SourceMutationResult.Rejected(SourceMutationRejection.INVALID_NAME)
        val prepared = prepare(input)
            ?: return when (input) {
                is SourceInput.Xtream -> if (
                    !CredentialInputPolicy.isValidCredential(input.username) ||
                    !CredentialInputPolicy.isValidCredential(input.password)
                ) {
                    SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CREDENTIALS)
                } else {
                    SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
                }

                is SourceInput.M3u -> SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
            }

        val existingSources = try {
            sourceDao.getAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
        if (existingSources.any { row ->
                row.type == prepared.type.name && row.baseLocator == prepared.safeLocator
            }
        ) {
            return SourceMutationResult.Rejected(SourceMutationRejection.DUPLICATE_SOURCE)
        }

        val priorSelectedSourceId = try {
            activeSourceStore.currentSelectedSourceId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
        val sourceId = newSourceId()
        val attemptAt = nowMillis()
        if (prepared.type == SourceType.XTREAM && catalogLoader is StagedSourceCatalogLoader) {
            return addXtreamWithLiveStage(
                displayName = displayName,
                sourceId = sourceId,
                prepared = prepared,
                attemptAt = attemptAt,
                priorSelectedSourceId = priorSelectedSourceId,
                catalogLoader = catalogLoader,
            )
        }
        val snapshot = when (
            val validation = validateCatalog(
                sourceId = sourceId,
                prepared = prepared,
            )
        ) {
            is CatalogValidation.Valid -> validation.snapshot
            is CatalogValidation.Rejected ->
                return SourceMutationResult.Rejected(validation.reason)
        }
        val completedAt = nowMillis()
        val entity = SourceEntity(
            sourceId = sourceId.value,
            displayName = displayName,
            type = prepared.type.name,
            baseLocator = prepared.safeLocator,
            credentialReference = sourceId.value,
            enabled = true,
            createdAt = attemptAt,
            updatedAt = attemptAt,
        )
        var sourceInserted = false

        return try {
            credentialStore.put(sourceId, prepared.secret)
            sourceDao.insert(entity)
            sourceInserted = true
            catalogRefreshStore.commitSuccessfulRefresh(
                sourceId = sourceId,
                sourceType = prepared.type,
                generation = 1L,
                snapshot = snapshot,
                attemptAtEpochMs = attemptAt,
                completedAtEpochMs = completedAt,
            )
            activeSourceStore.setSelectedSourceId(sourceId.value)
            SourceMutationResult.Success(sourceId)
        } catch (_: Exception) {
            if (sourceInserted) {
                runCatching { sourceDao.delete(sourceId.value) }
            }
            runCatching { credentialStore.delete(sourceId) }
            runCatching { activeSourceStore.setSelectedSourceId(priorSelectedSourceId) }
            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
    }

    private suspend fun addXtreamWithLiveStage(
        displayName: String,
        sourceId: SourceId,
        prepared: PreparedSource,
        attemptAt: Long,
        priorSelectedSourceId: String?,
        catalogLoader: StagedSourceCatalogLoader,
    ): SourceMutationResult {
        val source = SourceEntity(
            sourceId = sourceId.value,
            displayName = displayName,
            type = prepared.type.name,
            baseLocator = prepared.safeLocator,
            credentialReference = sourceId.value,
            enabled = true,
            createdAt = attemptAt,
            updatedAt = attemptAt,
        )
        val ready = CompletableDeferred<SourceMutationResult>()
        val liveCommitted = AtomicBoolean(false)
        val loadJob = stagedCatalogScope.launch(start = CoroutineStart.LAZY) {
            var sourceMayExist = false
            var credentialMayExist = false
            var generation = 1L
            try {
                val completeSnapshot = catalogLoader.loadWithLiveStage(
                    sourceId = sourceId,
                    sourceType = prepared.type,
                    baseLocator = prepared.safeLocator,
                    secret = prepared.secret,
                ) { liveSnapshot ->
                    if (liveCommitted.get()) return@loadWithLiveStage
                    require(
                        CatalogSection.LIVE_CATEGORIES in liveSnapshot.authoritativeSections &&
                            CatalogSection.LIVE_CHANNELS in liveSnapshot.authoritativeSections,
                    ) { "Live stage must contain authoritative categories and channels" }

                    try {
                        credentialMayExist = true
                        credentialStore.put(sourceId, prepared.secret)
                        sourceMayExist = true
                        sourceDao.insert(source)
                        catalogRefreshStore.commitSuccessfulRefresh(
                            sourceId = sourceId,
                            sourceType = prepared.type,
                            generation = generation,
                            snapshot = liveSnapshot.copy(initializing = true),
                            attemptAtEpochMs = attemptAt,
                            completedAtEpochMs = nowMillis(),
                        )
                        activeSourceStore.setSelectedSourceId(sourceId.value)
                        liveCommitted.set(true)
                        ready.complete(SourceMutationResult.Success(sourceId))
                    } catch (cancelled: CancellationException) {
                        rollbackNewSource(
                            sourceId,
                            priorSelectedSourceId,
                            sourceMayExist,
                            credentialMayExist,
                        )
                        ready.completeExceptionally(cancelled)
                        throw cancelled
                    } catch (error: Exception) {
                        rollbackNewSource(
                            sourceId,
                            priorSelectedSourceId,
                            sourceMayExist,
                            credentialMayExist,
                        )
                        ready.complete(
                            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE),
                        )
                        throw error
                    }
                }

                if (!liveCommitted.get()) {
                    // Providers that cannot return Live data still publish only a complete snapshot.
                    credentialMayExist = true
                    credentialStore.put(sourceId, prepared.secret)
                    sourceMayExist = true
                    sourceDao.insert(source)
                    catalogRefreshStore.commitSuccessfulRefresh(
                        sourceId = sourceId,
                        sourceType = prepared.type,
                        generation = generation,
                        snapshot = completeSnapshot,
                        attemptAtEpochMs = attemptAt,
                        completedAtEpochMs = nowMillis(),
                    )
                    activeSourceStore.setSelectedSourceId(sourceId.value)
                    liveCommitted.set(true)
                    ready.complete(SourceMutationResult.Success(sourceId))
                } else {
                    generation += 1L
                    catalogRefreshStore.commitSuccessfulRefresh(
                        sourceId = sourceId,
                        sourceType = prepared.type,
                        generation = generation,
                        snapshot = completeSnapshot.copy(initializing = false),
                        attemptAtEpochMs = attemptAt,
                        completedAtEpochMs = nowMillis(),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!liveCommitted.get()) {
                    rollbackNewSource(
                        sourceId,
                        priorSelectedSourceId,
                        sourceMayExist,
                        credentialMayExist,
                    )
                    ready.completeExceptionally(cancelled)
                }
                throw cancelled
            } catch (error: Exception) {
                if (!liveCommitted.get()) {
                    rollbackNewSource(
                        sourceId,
                        priorSelectedSourceId,
                        sourceMayExist,
                        credentialMayExist,
                    )
                    ready.complete(rejectionForInitialCatalog(error, prepared.type))
                } else {
                    val previous = try {
                        refreshStateDao.get(sourceId.value)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val category = (error as? CatalogLoadException)?.category
                        ?: SourceRefreshFailureCategory.STORAGE
                    try {
                        recordRefreshFailure(sourceId, previous, attemptAt, category)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The last committed Live catalog remains usable if error state cannot be saved.
                    }
                }
            }
        }
        check(stagedCatalogJobs.putIfAbsent(sourceId, loadJob) == null)
        loadJob.invokeOnCompletion { stagedCatalogJobs.remove(sourceId, loadJob) }
        loadJob.start()

        return try {
            ready.await()
        } catch (cancelled: CancellationException) {
            if (!liveCommitted.get()) {
                withContext(NonCancellable) { loadJob.cancelAndJoin() }
            }
            throw cancelled
        }
    }

    private suspend fun rollbackNewSource(
        sourceId: SourceId,
        priorSelectedSourceId: String?,
        sourceMayExist: Boolean,
        credentialMayExist: Boolean,
    ) = withContext(NonCancellable) {
        var rollbackCancellation: CancellationException? = null
        if (sourceMayExist) {
            try {
                sourceDao.delete(sourceId.value)
            } catch (cancelled: CancellationException) {
                rollbackCancellation = cancelled
            } catch (_: Exception) {
                // Continue removing credentials and restoring selection.
            }
        }
        if (credentialMayExist) {
            try {
                credentialStore.delete(sourceId)
            } catch (cancelled: CancellationException) {
                if (rollbackCancellation == null) rollbackCancellation = cancelled
            } catch (_: Exception) {
                // Continue restoring active-source selection.
            }
        }
        try {
            activeSourceStore.setSelectedSourceId(priorSelectedSourceId)
        } catch (cancelled: CancellationException) {
            if (rollbackCancellation == null) rollbackCancellation = cancelled
        } catch (_: Exception) {
            // Source and credential rollback remain the primary atomicity boundary.
        }
        rollbackCancellation?.let { throw it }
    }

    private fun rejectionForInitialCatalog(
        error: Exception,
        sourceType: SourceType,
    ): SourceMutationResult.Rejected {
        val category = (error as? CatalogLoadException)?.category
        val reason = if (
            sourceType == SourceType.XTREAM &&
            category == SourceRefreshFailureCategory.AUTHENTICATION
        ) {
            SourceMutationRejection.INVALID_CREDENTIALS
        } else {
            SourceMutationRejection.INVALID_CONNECTION
        }
        return SourceMutationResult.Rejected(reason)
    }

    override suspend fun reconnectSource(
        sourceId: SourceId,
        input: SourceReconnectInput,
    ): SourceMutationResult {
        val source = try {
            sourceDao.get(sourceId.value)
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        } ?: return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        val refreshState = try {
            refreshStateDao.get(sourceId.value)
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
        val authenticationRequired =
            refreshState?.errorCode == SourceRefreshFailureCategory.AUTHENTICATION.name
        if (source.enabled && !authenticationRequired) {
            return SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
        }

        val prepared = prepareReconnect(source.displayName, input)
            ?: return when (input) {
                is SourceReconnectInput.Xtream -> if (
                    !CredentialInputPolicy.isValidCredential(input.username) ||
                    !CredentialInputPolicy.isValidCredential(input.password)
                ) {
                    SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CREDENTIALS)
                } else {
                    SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
                }

                is SourceReconnectInput.M3u ->
                    SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
            }
        if (source.type != prepared.type.name || source.baseLocator != prepared.safeLocator) {
            return SourceMutationResult.Rejected(SourceMutationRejection.INVALID_CONNECTION)
        }

        val previousSecret = try {
            credentialStore.get(sourceId)
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
        val attemptAt = nowMillis()
        val snapshot = when (
            val validation = validateCatalog(
                sourceId = sourceId,
                prepared = prepared,
            )
        ) {
            is CatalogValidation.Valid -> validation.snapshot
            is CatalogValidation.Rejected ->
                return SourceMutationResult.Rejected(validation.reason)
        }
        val completedAt = nowMillis()
        val generation = (refreshState?.generation ?: 0L) + 1L

        return try {
            credentialStore.put(sourceId, prepared.secret)
            sourceDao.update(
                source.copy(
                    credentialReference = sourceId.value,
                    enabled = true,
                    updatedAt = completedAt,
                ),
            )
            catalogRefreshStore.commitSuccessfulRefresh(
                sourceId = sourceId,
                sourceType = prepared.type,
                generation = generation,
                snapshot = snapshot,
                attemptAtEpochMs = attemptAt,
                completedAtEpochMs = completedAt,
            )
            SourceMutationResult.Success(sourceId)
        } catch (_: Exception) {
            runCatching { sourceDao.update(source) }
            runCatching {
                if (previousSecret == null) {
                    credentialStore.delete(sourceId)
                } else {
                    credentialStore.put(sourceId, previousSecret)
                }
            }
            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
    }

    override suspend fun setActiveSource(sourceId: SourceId): Boolean {
        val source = try {
            sourceDao.get(sourceId.value)
        } catch (_: Exception) {
            return false
        } ?: return false
        if (!source.enabled) return false

        return try {
            activeSourceStore.setSelectedSourceId(sourceId.value)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun clearActiveSource(): Boolean =
        try {
            activeSourceStore.setSelectedSourceId(null)
            true
        } catch (_: Exception) {
            false
        }

    override suspend fun renameSource(
        sourceId: SourceId,
        displayName: String,
    ): SourceMutationResult {
        val normalizedName = CredentialInputPolicy.normalizeDisplayName(displayName)
            ?: return SourceMutationResult.Rejected(SourceMutationRejection.INVALID_NAME)
        val source = try {
            sourceDao.get(sourceId.value)
        } catch (_: Exception) {
            return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        } ?: return SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        if (source.displayName == normalizedName) {
            return SourceMutationResult.Success(sourceId)
        }
        return try {
            sourceDao.update(
                source.copy(
                    displayName = normalizedName,
                    updatedAt = nowMillis(),
                ),
            )
            SourceMutationResult.Success(sourceId)
        } catch (_: Exception) {
            SourceMutationResult.Rejected(SourceMutationRejection.STORAGE_FAILURE)
        }
    }

    override suspend fun refreshSource(sourceId: SourceId): SourceRefreshResult =
        refreshSourceLocked(sourceId, skipIfBusy = false)

    override suspend fun refreshSourceIfIdle(sourceId: SourceId): SourceRefreshResult =
        refreshSourceLocked(sourceId, skipIfBusy = true)

    private suspend fun refreshSourceLocked(
        sourceId: SourceId,
        skipIfBusy: Boolean,
    ): SourceRefreshResult {
        if (stagedCatalogJobs[sourceId]?.isActive == true) return SourceRefreshResult.Skipped
        val acquired = if (skipIfBusy) refreshMutex.tryLock() else {
            refreshMutex.lock()
            true
        }
        if (!acquired) return SourceRefreshResult.Skipped
        try {
            if (stagedCatalogJobs[sourceId]?.isActive == true) return SourceRefreshResult.Skipped
            val source = try {
                sourceDao.get(sourceId.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return storageFailure()
            } ?: return SourceRefreshResult.Failure(
                category = SourceRefreshFailureCategory.UNKNOWN,
                safeMessage = "Source is unavailable.",
            )
            if (!source.enabled) {
                return SourceRefreshResult.Failure(
                    category = SourceRefreshFailureCategory.UNKNOWN,
                    safeMessage = "Source is disabled.",
                )
            }

            val sourceType = try {
                SourceType.valueOf(source.type)
            } catch (_: Exception) {
                return SourceRefreshResult.Failure(
                    category = SourceRefreshFailureCategory.UNKNOWN,
                    safeMessage = "Source type is unavailable.",
                )
            }

            val secret = try {
                credentialStore.get(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return storageFailure()
            } ?: return SourceRefreshResult.Failure(
                category = SourceRefreshFailureCategory.AUTHENTICATION,
                safeMessage = "Source credentials are unavailable.",
            )

            val previous = try {
                refreshStateDao.get(sourceId.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return storageFailure()
            }
            val attemptAt = nowMillis()
            val generation = (previous?.generation ?: 0L) + 1L

            val snapshot = try {
                catalogLoader.load(
                    sourceId = sourceId,
                    sourceType = sourceType,
                    baseLocator = source.baseLocator,
                    secret = secret,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CatalogLoadException) {
                return recordRefreshFailure(
                    sourceId = sourceId,
                    previous = previous,
                    attemptAt = attemptAt,
                    category = error.category,
                )
            } catch (_: Exception) {
                return recordRefreshFailure(
                    sourceId = sourceId,
                    previous = previous,
                    attemptAt = attemptAt,
                    category = SourceRefreshFailureCategory.UNKNOWN,
                )
            }

            return try {
                catalogRefreshStore.commitSuccessfulRefresh(
                    sourceId = sourceId,
                    sourceType = sourceType,
                    generation = generation,
                    snapshot = snapshot,
                    attemptAtEpochMs = attemptAt,
                    completedAtEpochMs = nowMillis(),
                )
                if (snapshot.failedSections.isEmpty()) {
                    SourceRefreshResult.Success
                } else {
                    SourceRefreshResult.Partial(
                        snapshot.failedSections.keys.map(CatalogSection::name).toSet(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordRefreshFailure(
                    sourceId = sourceId,
                    previous = previous,
                    attemptAt = attemptAt,
                    category = SourceRefreshFailureCategory.STORAGE,
                )
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    override suspend fun removeSource(sourceId: SourceId): Boolean {
        stagedCatalogJobs[sourceId]?.let { stagedJob ->
            withContext(NonCancellable) { stagedJob.cancelAndJoin() }
        }
        return refreshMutex.withLock {
            val existing = try {
                sourceDao.get(sourceId.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock false
            } ?: return@withLock false
            val priorSelectedSourceId = try {
                activeSourceStore.currentSelectedSourceId()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock false
            }
            val sources = try {
                sourceDao.getAll()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock false
            }
            val priorSecret = try {
                credentialStore.get(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock false
            }

            val removingPersistedActiveSource = priorSelectedSourceId == existing.sourceId
            val fallbackSourceId = if (removingPersistedActiveSource) {
                sources.firstOrNull { row ->
                    row.sourceId != existing.sourceId && row.enabled
                }?.sourceId
            } else {
                priorSelectedSourceId
            }

            // These local writes form the short commit window. Once it starts, finish the
            // delete or compensate before honoring cancellation so credentials, Room and
            // active-source selection cannot be stranded in different states.
            withContext(NonCancellable) {
                var activeSelectionChanged = false
                try {
                    if (removingPersistedActiveSource) {
                        activeSourceStore.setSelectedSourceId(fallbackSourceId)
                        activeSelectionChanged = true
                    }
                    credentialStore.delete(sourceId)
                    check(sourceDao.delete(sourceId.value) == 1) {
                        "Source row was not deleted"
                    }
                    true
                } catch (cancelled: CancellationException) {
                    compensateRemoval(
                        sourceId = sourceId,
                        priorSecret = priorSecret,
                        priorSelectedSourceId = priorSelectedSourceId,
                        activeSelectionChanged = activeSelectionChanged,
                    )
                    throw cancelled
                } catch (_: Exception) {
                    val stillExists = runCatching { sourceDao.get(sourceId.value) != null }.getOrDefault(true)
                    if (!stillExists) {
                        // A database adapter may report cancellation after the DELETE committed.
                        true
                    } else {
                        compensateRemoval(
                            sourceId = sourceId,
                            priorSecret = priorSecret,
                            priorSelectedSourceId = priorSelectedSourceId,
                            activeSelectionChanged = activeSelectionChanged,
                        )
                        false
                    }
                }
            }
        }
    }

    private suspend fun compensateRemoval(
        sourceId: SourceId,
        priorSecret: SourceSecret?,
        priorSelectedSourceId: String?,
        activeSelectionChanged: Boolean,
    ) {
        val sourceStillExists = try {
            sourceDao.get(sourceId.value) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
        if (!sourceStillExists) return
        var compensationCancellation: CancellationException? = null
        // A store may commit a delete and then report cancellation/failure before returning.
        // Re-putting the already-read secret is idempotent and repairs that ambiguous outcome.
        if (priorSecret != null) {
            try {
                credentialStore.put(sourceId, priorSecret)
            } catch (cancelled: CancellationException) {
                compensationCancellation = cancelled
            } catch (_: Exception) {
                // Keep attempting active-source restoration even if credential repair failed.
            }
        }
        if (activeSelectionChanged) {
            try {
                activeSourceStore.setSelectedSourceId(priorSelectedSourceId)
            } catch (cancelled: CancellationException) {
                if (compensationCancellation == null) compensationCancellation = cancelled
            } catch (_: Exception) {
                // Both remaining source row and its active selection are otherwise recoverable.
            }
        }
        compensationCancellation?.let { throw it }
    }

    private suspend fun recordRefreshFailure(
        sourceId: SourceId,
        previous: RefreshStateEntity?,
        attemptAt: Long,
        category: SourceRefreshFailureCategory,
    ): SourceRefreshResult {
        return try {
            refreshStateDao.upsert(
                RefreshStateEntity(
                    sourceId = sourceId.value,
                    generation = previous?.generation ?: 0L,
                    state = "FAILED",
                    lastAttempt = attemptAt,
                    lastSuccess = previous?.lastSuccess,
                    errorCode = category.name,
                ),
            )
            SourceRefreshResult.Failure(
                category = category,
                safeMessage = category.safeMessage(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storageFailure()
        }
    }

    private fun SourceRefreshFailureCategory.safeMessage(): String = when (this) {
        SourceRefreshFailureCategory.AUTHENTICATION -> "Source authentication failed."
        SourceRefreshFailureCategory.NETWORK -> "Source network request failed."
        SourceRefreshFailureCategory.TIMEOUT -> "Source request timed out."
        SourceRefreshFailureCategory.INVALID_PAYLOAD -> "Source returned invalid catalog data."
        SourceRefreshFailureCategory.PROVIDER -> "Source provider rejected the refresh."
        SourceRefreshFailureCategory.TRANSIENT_PROVIDER -> "Source provider is temporarily unavailable."
        SourceRefreshFailureCategory.STORAGE -> "Source refresh could not be saved."
        SourceRefreshFailureCategory.UNKNOWN -> "Source refresh failed."
    }

    private fun storageFailure(): SourceRefreshResult.Failure = SourceRefreshResult.Failure(
        category = SourceRefreshFailureCategory.STORAGE,
        safeMessage = SourceRefreshFailureCategory.STORAGE.safeMessage(),
    )

    private suspend fun validateCatalog(
        sourceId: SourceId,
        prepared: PreparedSource,
    ): CatalogValidation = try {
        CatalogValidation.Valid(
            catalogLoader.load(
                sourceId = sourceId,
                sourceType = prepared.type,
                baseLocator = prepared.safeLocator,
                secret = prepared.secret,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: CatalogLoadException) {
        CatalogValidation.Rejected(
            when {
                prepared.type == SourceType.XTREAM &&
                    error.category == SourceRefreshFailureCategory.AUTHENTICATION ->
                    SourceMutationRejection.INVALID_CREDENTIALS

                else -> SourceMutationRejection.INVALID_CONNECTION
            },
        )
    } catch (_: Exception) {
        CatalogValidation.Rejected(SourceMutationRejection.INVALID_CONNECTION)
    }

    private fun prepareReconnect(
        displayName: String,
        input: SourceReconnectInput,
    ): PreparedSource? = when (input) {
        is SourceReconnectInput.Xtream -> prepare(
            SourceInput.Xtream(
                displayName = displayName,
                serverUrl = input.serverUrl,
                username = input.username,
                password = input.password,
            ),
        )

        is SourceReconnectInput.M3u -> prepare(
            SourceInput.M3u(
                displayName = displayName,
                playlistUrl = input.playlistUrl,
                epgUrl = input.epgUrl,
            ),
        )
    }

    private fun prepare(input: SourceInput): PreparedSource? {
        return when (input) {
            is SourceInput.Xtream -> {
                if (
                    !CredentialInputPolicy.isValidCredential(input.username) ||
                    !CredentialInputPolicy.isValidCredential(input.password)
                ) {
                    null
                } else {
                    val normalizedBaseUrl = SourceConnectionSecurityPolicy
                        .normalizeXtreamBaseUrl(input.serverUrl)
                        .normalizedUrlOrNull()
                        ?: return null
                    PreparedSource(
                        type = SourceType.XTREAM,
                        safeLocator = normalizedBaseUrl,
                        secret = SourceSecret.Xtream(
                            username = input.username,
                            password = input.password,
                        ),
                    )
                }
            }

            is SourceInput.M3u -> {
                val playlistUrl = SourceConnectionSecurityPolicy
                    .normalizeRemoteMediaUrl(input.playlistUrl)
                    .normalizedUrlOrNull()
                    ?: return null
                val epgUrl = input.epgUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { raw ->
                        SourceConnectionSecurityPolicy
                            .normalizeRemoteMediaUrl(raw)
                            .normalizedUrlOrNull()
                            ?: return null
                    }
                PreparedSource(
                    type = SourceType.M3U,
                    safeLocator = SourceLocatorPolicy.redact(playlistUrl),
                    secret = SourceSecret.M3uRemote(
                        playlistUrl = playlistUrl,
                        epgUrl = epgUrl,
                    ),
                )
            }
        }
    }

    private fun SourceEntity.toSummary(refreshState: RefreshStateEntity?): SourceSummary =
        SourceSummary(
            sourceId = SourceId(sourceId),
            type = SourceType.valueOf(type),
            displayName = displayName,
            connectionLabel = SourceLocatorPolicy.connectionLabel(baseLocator),
            enabled = enabled,
            lastSuccessfulRefreshAtEpochMs = refreshState?.lastSuccess,
            refreshFailureCategory = refreshState?.errorCode
                ?.let { value -> runCatching { SourceRefreshFailureCategory.valueOf(value) }.getOrNull() },
        )

    private fun ConnectionValidation.normalizedUrlOrNull(): String? =
        (this as? ConnectionValidation.Valid)?.normalizedUrl

    private sealed interface CatalogValidation {
        data class Valid(val snapshot: ProviderCatalogSnapshot) : CatalogValidation
        data class Rejected(val reason: SourceMutationRejection) : CatalogValidation
    }

    private data class PreparedSource(
        val type: SourceType,
        val safeLocator: String,
        val secret: SourceSecret,
    )
}
