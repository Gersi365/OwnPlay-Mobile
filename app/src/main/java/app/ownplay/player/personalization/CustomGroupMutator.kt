package app.ownplay.player.personalization

import androidx.room.withTransaction
import app.ownplay.player.persistence.CustomGroupEntity
import app.ownplay.player.persistence.CustomGroupMembershipEntity
import app.ownplay.player.persistence.OwnPlayDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class CustomGroupFailureReason {
    INVALID_GROUP_ID,
    INVALID_GROUP_NAME,
    INVALID_SOURCE_ID,
    INVALID_TIMESTAMP,
    GROUP_NOT_FOUND,
    EMPTY_SELECTION,
    EMPTY_CHANNEL_ID,
    CHANNEL_NOT_FOUND,
    PERSISTENCE_FAILURE,
}

sealed interface CustomGroupMutationResult {
    data class Success(
        val groupId: String,
        val channelIds: List<String> = emptyList(),
    ) : CustomGroupMutationResult

    data class Failure(
        val reason: CustomGroupFailureReason,
        val groupId: String? = null,
        val channelId: String? = null,
    ) : CustomGroupMutationResult
}

class CustomGroupMutator(
    private val database: OwnPlayDatabase,
) {
    suspend fun createGroup(
        name: String,
        createdAtEpochMillis: Long,
        groupId: String = UUID.randomUUID().toString(),
    ): CustomGroupMutationResult {
        val normalizedName = name.trim()
        if (groupId.isBlank()) {
            return CustomGroupMutationResult.Failure(CustomGroupFailureReason.INVALID_GROUP_ID)
        }
        if (normalizedName.isEmpty()) {
            return CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.INVALID_GROUP_NAME,
                groupId = groupId,
            )
        }
        if (createdAtEpochMillis < 0) {
            return CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.INVALID_TIMESTAMP,
                groupId = groupId,
            )
        }

        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                val groups = dao.customGroupsForMutation()
                if (groups.any { it.groupId == groupId }) {
                    return@withTransaction CustomGroupMutationResult.Failure(
                        reason = CustomGroupFailureReason.INVALID_GROUP_ID,
                        groupId = groupId,
                    )
                }
                val nextOrder = (groups.maxOfOrNull(CustomGroupEntity::groupOrder) ?: -1L) + 1L
                val group = CustomGroupEntity(
                    groupId = groupId,
                    name = normalizedName,
                    groupOrder = nextOrder,
                    createdAtEpochMillis = createdAtEpochMillis,
                )
                dao.upsertGroup(group)
                CustomGroupMutationResult.Success(groupId = groupId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.PERSISTENCE_FAILURE,
                groupId = groupId,
            )
        }
    }

    suspend fun renameGroup(
        groupId: String,
        name: String,
    ): CustomGroupMutationResult {
        val normalizedName = name.trim()
        if (groupId.isBlank()) {
            return CustomGroupMutationResult.Failure(CustomGroupFailureReason.INVALID_GROUP_ID)
        }
        if (normalizedName.isEmpty()) {
            return CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.INVALID_GROUP_NAME,
                groupId = groupId,
            )
        }
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                val existing = dao.customGroupById(groupId)
                    ?: return@withTransaction CustomGroupMutationResult.Failure(
                        reason = CustomGroupFailureReason.GROUP_NOT_FOUND,
                        groupId = groupId,
                    )
                val updated = existing.copy(name = normalizedName)
                dao.upsertGroup(updated)
                CustomGroupMutationResult.Success(groupId = groupId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.PERSISTENCE_FAILURE,
                groupId = groupId,
            )
        }
    }

    suspend fun deleteGroup(groupId: String): CustomGroupMutationResult {
        if (groupId.isBlank()) {
            return CustomGroupMutationResult.Failure(CustomGroupFailureReason.INVALID_GROUP_ID)
        }
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                if (dao.customGroupById(groupId) == null) {
                    return@withTransaction CustomGroupMutationResult.Failure(
                        reason = CustomGroupFailureReason.GROUP_NOT_FOUND,
                        groupId = groupId,
                    )
                }
                val deleted = dao.deleteCustomGroup(groupId)
                if (deleted == 0) {
                    CustomGroupMutationResult.Failure(
                        reason = CustomGroupFailureReason.GROUP_NOT_FOUND,
                        groupId = groupId,
                    )
                } else {
                    CustomGroupMutationResult.Success(groupId = groupId)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.PERSISTENCE_FAILURE,
                groupId = groupId,
            )
        }
    }

    suspend fun addChannels(
        sourceId: String,
        groupId: String,
        channelIds: Set<String>,
    ): CustomGroupMutationResult = mutateMemberships(
        sourceId = sourceId,
        groupId = groupId,
        requestedChannelIds = channelIds,
    ) { orderedSelection, existing ->
        CustomGroupMembershipPlanner.add(
            groupId = groupId,
            existing = existing,
            selectedChannelIdsInSourceOrder = orderedSelection,
        )
    }

    suspend fun removeChannels(
        sourceId: String,
        groupId: String,
        channelIds: Set<String>,
    ): CustomGroupMutationResult = mutateMemberships(
        sourceId = sourceId,
        groupId = groupId,
        requestedChannelIds = channelIds,
    ) { orderedSelection, existing ->
        val dao = database.personalizationDao()
        orderedSelection.forEach { channelId ->
            dao.removeGroupMembership(groupId, channelId)
        }
        CustomGroupMembershipPlanner.remove(
            groupId = groupId,
            existing = existing,
            channelIdsToRemove = orderedSelection.toSet(),
        )
    }

    private suspend fun mutateMemberships(
        sourceId: String,
        groupId: String,
        requestedChannelIds: Set<String>,
        operation: suspend (
            orderedSelection: List<String>,
            existing: List<CustomGroupMembershipEntity>,
        ) -> CustomGroupMembershipPlan,
    ): CustomGroupMutationResult {
        if (sourceId.isBlank()) {
            return CustomGroupMutationResult.Failure(CustomGroupFailureReason.INVALID_SOURCE_ID)
        }
        if (groupId.isBlank()) {
            return CustomGroupMutationResult.Failure(CustomGroupFailureReason.INVALID_GROUP_ID)
        }
        return try {
            database.withTransaction {
                val dao = database.personalizationDao()
                if (dao.customGroupById(groupId) == null) {
                    return@withTransaction CustomGroupMutationResult.Failure(
                        reason = CustomGroupFailureReason.GROUP_NOT_FOUND,
                        groupId = groupId,
                    )
                }
                val sourceOrder = dao.resolvedChannelOrder(sourceId)
                when (
                    val validation = ChannelSelectionValidator.validate(
                        requestedChannelIds = requestedChannelIds,
                        availableChannelIdsInOrder = sourceOrder,
                    )
                ) {
                    is ChannelSelectionValidationResult.Failure -> validation.toCustomGroupFailure(groupId)
                    is ChannelSelectionValidationResult.Success -> {
                        val existing = dao.groupMemberships(groupId)
                        val plan = operation(validation.channelIds, existing)
                        dao.upsertGroupMemberships(plan.memberships)
                        CustomGroupMutationResult.Success(
                            groupId = groupId,
                            channelIds = plan.channelIds,
                        )
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CustomGroupMutationResult.Failure(
                reason = CustomGroupFailureReason.PERSISTENCE_FAILURE,
                groupId = groupId,
            )
        }
    }
}

private fun ChannelSelectionValidationResult.Failure.toCustomGroupFailure(
    groupId: String,
): CustomGroupMutationResult.Failure = CustomGroupMutationResult.Failure(
    reason = when (reason) {
        ChannelSelectionFailureReason.EMPTY_SELECTION -> CustomGroupFailureReason.EMPTY_SELECTION
        ChannelSelectionFailureReason.EMPTY_CHANNEL_ID -> CustomGroupFailureReason.EMPTY_CHANNEL_ID
        ChannelSelectionFailureReason.CHANNEL_NOT_FOUND -> CustomGroupFailureReason.CHANNEL_NOT_FOUND
    },
    groupId = groupId,
    channelId = channelId,
)
