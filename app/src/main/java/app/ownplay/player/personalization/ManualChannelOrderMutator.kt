package app.ownplay.player.personalization

import androidx.room.withTransaction
import app.ownplay.player.persistence.ChannelCustomizationEntity
import app.ownplay.player.persistence.OwnPlayDatabase
import kotlinx.coroutines.CancellationException

sealed interface ManualOrderMutationResult {
    data class Success(val plan: ManualOrderPlan) : ManualOrderMutationResult

    data class Rejected(
        val failure: ManualOrderPlanResult.Failure,
    ) : ManualOrderMutationResult

    data object InvalidSourceId : ManualOrderMutationResult
    data object PersistenceFailure : ManualOrderMutationResult
}

class ManualChannelOrderMutator(
    private val database: OwnPlayDatabase,
) {
    suspend fun move(
        sourceId: String,
        channelId: String,
        targetIndex: Int,
    ): ManualOrderMutationResult = mutate(sourceId) { currentOrder ->
        ManualChannelOrderPlanner.move(
            currentOrder = currentOrder,
            channelId = channelId,
            targetIndex = targetIndex,
        )
    }

    suspend fun moveRelative(
        sourceId: String,
        channelId: String,
        anchorChannelId: String,
        placement: ManualOrderPlacement,
    ): ManualOrderMutationResult = mutate(sourceId) { currentOrder ->
        ManualChannelOrderPlanner.moveRelative(
            currentOrder = currentOrder,
            channelId = channelId,
            anchorChannelId = anchorChannelId,
            placement = placement,
        )
    }

    suspend fun moveSelectedToTop(
        sourceId: String,
        selectedChannelIds: Set<String>,
    ): ManualOrderMutationResult = mutate(sourceId) { currentOrder ->
        ManualChannelOrderPlanner.moveSelectedToTop(
            currentOrder = currentOrder,
            selectedChannelIds = selectedChannelIds,
        )
    }

    suspend fun moveSelectedToBottom(
        sourceId: String,
        selectedChannelIds: Set<String>,
    ): ManualOrderMutationResult = mutate(sourceId) { currentOrder ->
        ManualChannelOrderPlanner.moveSelectedToBottom(
            currentOrder = currentOrder,
            selectedChannelIds = selectedChannelIds,
        )
    }

    private suspend fun mutate(
        sourceId: String,
        operation: (List<String>) -> ManualOrderPlanResult,
    ): ManualOrderMutationResult {
        if (sourceId.isBlank()) return ManualOrderMutationResult.InvalidSourceId

        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                val currentOrder = dao.resolvedChannelOrder(sourceId)
                when (val result = operation(currentOrder)) {
                    is ManualOrderPlanResult.Failure -> ManualOrderMutationResult.Rejected(result)
                    is ManualOrderPlanResult.Success -> {
                        val existing = dao.customizationsForSource(sourceId)
                        val merged = ManualOrderCustomizationMerger.merge(
                            plan = result.plan,
                            existing = existing,
                        )
                        dao.upsertCustomizations(merged)
                        ManualOrderMutationResult.Success(result.plan)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ManualOrderMutationResult.PersistenceFailure
        }
    }
}

object ManualOrderCustomizationMerger {
    fun merge(
        plan: ManualOrderPlan,
        existing: List<ChannelCustomizationEntity>,
    ): List<ChannelCustomizationEntity> {
        val existingByChannelId = existing.associateBy(ChannelCustomizationEntity::channelId)
        return plan.assignments.map { assignment ->
            existingByChannelId[assignment.channelId]
                ?.copy(manualOrder = assignment.manualOrder)
                ?: ChannelCustomizationEntity(
                    channelId = assignment.channelId,
                    manualOrder = assignment.manualOrder,
                )
        }
    }
}
