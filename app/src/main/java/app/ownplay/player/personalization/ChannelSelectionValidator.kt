package app.ownplay.player.personalization

enum class ChannelSelectionFailureReason {
    EMPTY_SELECTION,
    EMPTY_CHANNEL_ID,
    CHANNEL_NOT_FOUND,
}

sealed interface ChannelSelectionValidationResult {
    data class Success(val channelIds: List<String>) : ChannelSelectionValidationResult

    data class Failure(
        val reason: ChannelSelectionFailureReason,
        val channelId: String? = null,
    ) : ChannelSelectionValidationResult
}

object ChannelSelectionValidator {
    fun validate(
        requestedChannelIds: Set<String>,
        availableChannelIdsInOrder: List<String>,
    ): ChannelSelectionValidationResult {
        if (requestedChannelIds.isEmpty()) {
            return ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.EMPTY_SELECTION,
            )
        }
        if (requestedChannelIds.any(String::isBlank)) {
            return ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.EMPTY_CHANNEL_ID,
            )
        }

        val available = availableChannelIdsInOrder.toHashSet()
        val missing = requestedChannelIds
            .asSequence()
            .filterNot(available::contains)
            .sorted()
            .firstOrNull()
        if (missing != null) {
            return ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.CHANNEL_NOT_FOUND,
                channelId = missing,
            )
        }

        return ChannelSelectionValidationResult.Success(
            channelIds = availableChannelIdsInOrder.filter(requestedChannelIds::contains),
        )
    }
}
