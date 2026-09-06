package app.ownplay.player.personalization

import androidx.room.withTransaction
import app.ownplay.player.persistence.FavoriteEntryEntity
import app.ownplay.player.persistence.OwnPlayDatabase
import kotlinx.coroutines.CancellationException

enum class FavoriteMutationFailureReason {
    INVALID_SOURCE_ID,
    INVALID_TIMESTAMP,
    EMPTY_SELECTION,
    EMPTY_CHANNEL_ID,
    CHANNEL_NOT_FOUND,
    FAVORITE_NOT_FOUND,
    TARGET_INDEX_OUT_OF_RANGE,
    PERSISTENCE_FAILURE,
}

sealed interface FavoriteMutationResult {
    data class Success(val favoriteChannelIds: List<String>) : FavoriteMutationResult

    data class Failure(
        val reason: FavoriteMutationFailureReason,
        val channelId: String? = null,
    ) : FavoriteMutationResult
}

class FavoriteChannelMutator(
    private val database: OwnPlayDatabase,
) {
    suspend fun addFavorites(
        sourceId: String,
        channelIds: Set<String>,
        addedAtEpochMillis: Long,
    ): FavoriteMutationResult {
        if (addedAtEpochMillis < 0) {
            return FavoriteMutationResult.Failure(FavoriteMutationFailureReason.INVALID_TIMESTAMP)
        }
        return mutateSourceSelection(
            sourceId = sourceId,
            requestedChannelIds = channelIds,
        ) { orderedSelection, existing ->
            FavoriteEntryPlanner.add(
                existing = existing,
                selectedChannelIdsInSourceOrder = orderedSelection,
                addedAtEpochMillis = addedAtEpochMillis,
            )
        }
    }

    suspend fun removeFavorites(
        sourceId: String,
        channelIds: Set<String>,
    ): FavoriteMutationResult = mutateSourceSelection(
        sourceId = sourceId,
        requestedChannelIds = channelIds,
    ) { orderedSelection, existing ->
        val plan = FavoriteEntryPlanner.remove(
            existing = existing,
            channelIdsToRemove = orderedSelection.toSet(),
        )
        val dao = database.personalizationDao()
        orderedSelection.forEach { channelId -> dao.removeFavorite(channelId) }
        plan
    }

    suspend fun moveFavorite(
        sourceId: String,
        channelId: String,
        targetIndex: Int,
    ): FavoriteMutationResult = mutateFavoriteOrder(sourceId) { favoriteIds ->
        ManualChannelOrderPlanner.move(
            currentOrder = favoriteIds,
            channelId = channelId,
            targetIndex = targetIndex,
        )
    }

    suspend fun moveFavoriteRelative(
        sourceId: String,
        channelId: String,
        anchorChannelId: String,
        placement: ManualOrderPlacement,
    ): FavoriteMutationResult = mutateFavoriteOrder(sourceId) { favoriteIds ->
        ManualChannelOrderPlanner.moveRelative(
            currentOrder = favoriteIds,
            channelId = channelId,
            anchorChannelId = anchorChannelId,
            placement = placement,
        )
    }

    suspend fun moveSelectedFavoritesToTop(
        sourceId: String,
        channelIds: Set<String>,
    ): FavoriteMutationResult = mutateFavoriteSelection(sourceId, channelIds) { favoriteIds, selected ->
        ManualChannelOrderPlanner.moveSelectedToTop(
            currentOrder = favoriteIds,
            selectedChannelIds = selected.toSet(),
        )
    }

    suspend fun moveSelectedFavoritesToBottom(
        sourceId: String,
        channelIds: Set<String>,
    ): FavoriteMutationResult = mutateFavoriteSelection(sourceId, channelIds) { favoriteIds, selected ->
        ManualChannelOrderPlanner.moveSelectedToBottom(
            currentOrder = favoriteIds,
            selectedChannelIds = selected.toSet(),
        )
    }

    private suspend fun mutateSourceSelection(
        sourceId: String,
        requestedChannelIds: Set<String>,
        operation: suspend (
            orderedSelection: List<String>,
            existing: List<FavoriteEntryEntity>,
        ) -> FavoriteEntryPlan,
    ): FavoriteMutationResult {
        if (sourceId.isBlank()) {
            return FavoriteMutationResult.Failure(FavoriteMutationFailureReason.INVALID_SOURCE_ID)
        }
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                val sourceOrder = dao.resolvedChannelOrder(sourceId)
                when (
                    val validation = ChannelSelectionValidator.validate(
                        requestedChannelIds = requestedChannelIds,
                        availableChannelIdsInOrder = sourceOrder,
                    )
                ) {
                    is ChannelSelectionValidationResult.Failure -> validation.toFavoriteFailure()
                    is ChannelSelectionValidationResult.Success -> {
                        val existing = dao.favoriteEntriesForSource(sourceId)
                        val plan = operation(validation.channelIds, existing)
                        dao.upsertFavorites(plan.entries)
                        FavoriteMutationResult.Success(plan.channelIds)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            FavoriteMutationResult.Failure(FavoriteMutationFailureReason.PERSISTENCE_FAILURE)
        }
    }

    private suspend fun mutateFavoriteSelection(
        sourceId: String,
        requestedChannelIds: Set<String>,
        operation: (favoriteIds: List<String>, selected: List<String>) -> ManualOrderPlanResult,
    ): FavoriteMutationResult {
        if (sourceId.isBlank()) {
            return FavoriteMutationResult.Failure(FavoriteMutationFailureReason.INVALID_SOURCE_ID)
        }
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                val existing = dao.favoriteEntriesForSource(sourceId)
                val favoriteIds = existing.map { it.channelId }
                when (
                    val validation = ChannelSelectionValidator.validate(
                        requestedChannelIds = requestedChannelIds,
                        availableChannelIdsInOrder = favoriteIds,
                    )
                ) {
                    is ChannelSelectionValidationResult.Failure -> validation.toFavoriteFailure(
                        missingReason = FavoriteMutationFailureReason.FAVORITE_NOT_FOUND,
                    )
                    is ChannelSelectionValidationResult.Success -> applyFavoriteOrderPlan(
                        existing = existing,
                        plannerResult = operation(favoriteIds, validation.channelIds),
                    )
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            FavoriteMutationResult.Failure(FavoriteMutationFailureReason.PERSISTENCE_FAILURE)
        }
    }

    private suspend fun mutateFavoriteOrder(
        sourceId: String,
        operation: (favoriteIds: List<String>) -> ManualOrderPlanResult,
    ): FavoriteMutationResult {
        if (sourceId.isBlank()) {
            return FavoriteMutationResult.Failure(FavoriteMutationFailureReason.INVALID_SOURCE_ID)
        }
        return try {
            database.withTransaction {
                val existing = database.personalizationDao().favoriteEntriesForSource(sourceId)
                applyFavoriteOrderPlan(
                    existing = existing,
                    plannerResult = operation(existing.map { it.channelId }),
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            FavoriteMutationResult.Failure(FavoriteMutationFailureReason.PERSISTENCE_FAILURE)
        }
    }

    private suspend fun applyFavoriteOrderPlan(
        existing: List<FavoriteEntryEntity>,
        plannerResult: ManualOrderPlanResult,
    ): FavoriteMutationResult = when (plannerResult) {
        is ManualOrderPlanResult.Failure -> plannerResult.toFavoriteFailure()
        is ManualOrderPlanResult.Success -> {
            val plan = FavoriteEntryPlanner.reorder(existing, plannerResult.plan)
            database.personalizationDao().upsertFavorites(plan.entries)
            FavoriteMutationResult.Success(plan.channelIds)
        }
    }
}

private fun ChannelSelectionValidationResult.Failure.toFavoriteFailure(
    missingReason: FavoriteMutationFailureReason = FavoriteMutationFailureReason.CHANNEL_NOT_FOUND,
): FavoriteMutationResult.Failure = FavoriteMutationResult.Failure(
    reason = when (reason) {
        ChannelSelectionFailureReason.EMPTY_SELECTION -> FavoriteMutationFailureReason.EMPTY_SELECTION
        ChannelSelectionFailureReason.EMPTY_CHANNEL_ID -> FavoriteMutationFailureReason.EMPTY_CHANNEL_ID
        ChannelSelectionFailureReason.CHANNEL_NOT_FOUND -> missingReason
    },
    channelId = channelId,
)

private fun ManualOrderPlanResult.Failure.toFavoriteFailure(): FavoriteMutationResult.Failure =
    FavoriteMutationResult.Failure(
        reason = when (reason) {
            ManualOrderFailureReason.EMPTY_CHANNEL_ID -> FavoriteMutationFailureReason.EMPTY_CHANNEL_ID
            ManualOrderFailureReason.DUPLICATE_CHANNEL_ID,
            ManualOrderFailureReason.CHANNEL_NOT_FOUND,
            ManualOrderFailureReason.SELECTED_CHANNEL_NOT_FOUND,
            -> FavoriteMutationFailureReason.FAVORITE_NOT_FOUND
            ManualOrderFailureReason.TARGET_INDEX_OUT_OF_RANGE -> FavoriteMutationFailureReason.TARGET_INDEX_OUT_OF_RANGE
            ManualOrderFailureReason.EMPTY_SELECTION -> FavoriteMutationFailureReason.EMPTY_SELECTION
        },
        channelId = channelId,
    )
