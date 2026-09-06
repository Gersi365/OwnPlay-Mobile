package app.ownplay.player.personalization

import androidx.room.withTransaction
import app.ownplay.player.persistence.HiddenEntryEntity
import app.ownplay.player.persistence.OwnPlayDatabase
import kotlinx.coroutines.CancellationException

enum class ChannelVisibilityFailureReason {
    INVALID_SOURCE_ID,
    INVALID_TIMESTAMP,
    EMPTY_SELECTION,
    EMPTY_CHANNEL_ID,
    CHANNEL_NOT_FOUND,
    PERSISTENCE_FAILURE,
}

sealed interface ChannelVisibilityMutationResult {
    data class Success(val affectedChannelIds: List<String>) : ChannelVisibilityMutationResult

    data class Failure(
        val reason: ChannelVisibilityFailureReason,
        val channelId: String? = null,
    ) : ChannelVisibilityMutationResult
}

class ChannelVisibilityMutator(
    private val database: OwnPlayDatabase,
) {
    suspend fun hide(
        sourceId: String,
        channelIds: Set<String>,
        hiddenAtEpochMillis: Long,
    ): ChannelVisibilityMutationResult {
        if (hiddenAtEpochMillis < 0) {
            return ChannelVisibilityMutationResult.Failure(
                reason = ChannelVisibilityFailureReason.INVALID_TIMESTAMP,
            )
        }
        return mutate(
            sourceId = sourceId,
            requestedChannelIds = channelIds,
        ) { orderedChannelIds ->
            database.personalizationDao().upsertHidden(
                orderedChannelIds.map { channelId ->
                    HiddenEntryEntity(
                        channelId = channelId,
                        hiddenAtEpochMillis = hiddenAtEpochMillis,
                    )
                },
            )
        }
    }

    suspend fun unhide(
        sourceId: String,
        channelIds: Set<String>,
    ): ChannelVisibilityMutationResult = mutate(
        sourceId = sourceId,
        requestedChannelIds = channelIds,
    ) { orderedChannelIds ->
        val dao = database.personalizationDao()
        orderedChannelIds.forEach { channelId -> dao.unhide(channelId) }
    }

    private suspend fun mutate(
        sourceId: String,
        requestedChannelIds: Set<String>,
        mutation: suspend (List<String>) -> Unit,
    ): ChannelVisibilityMutationResult {
        if (sourceId.isBlank()) {
            return ChannelVisibilityMutationResult.Failure(
                reason = ChannelVisibilityFailureReason.INVALID_SOURCE_ID,
            )
        }

        return try {
            database.withTransaction {
                val availableOrder = database.personalizationDao().resolvedChannelOrder(sourceId)
                when (
                    val validation = ChannelSelectionValidator.validate(
                        requestedChannelIds = requestedChannelIds,
                        availableChannelIdsInOrder = availableOrder,
                    )
                ) {
                    is ChannelSelectionValidationResult.Failure ->
                        ChannelVisibilityMutationResult.Failure(
                            reason = validation.reason.toVisibilityFailureReason(),
                            channelId = validation.channelId,
                        )

                    is ChannelSelectionValidationResult.Success -> {
                        mutation(validation.channelIds)
                        ChannelVisibilityMutationResult.Success(validation.channelIds)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ChannelVisibilityMutationResult.Failure(
                reason = ChannelVisibilityFailureReason.PERSISTENCE_FAILURE,
            )
        }
    }
}

private fun ChannelSelectionFailureReason.toVisibilityFailureReason(): ChannelVisibilityFailureReason =
    when (this) {
        ChannelSelectionFailureReason.EMPTY_SELECTION -> ChannelVisibilityFailureReason.EMPTY_SELECTION
        ChannelSelectionFailureReason.EMPTY_CHANNEL_ID -> ChannelVisibilityFailureReason.EMPTY_CHANNEL_ID
        ChannelSelectionFailureReason.CHANNEL_NOT_FOUND -> ChannelVisibilityFailureReason.CHANNEL_NOT_FOUND
    }
