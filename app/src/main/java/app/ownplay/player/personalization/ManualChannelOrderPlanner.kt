package app.ownplay.player.personalization

data class ManualOrderAssignment(
    val channelId: String,
    val manualOrder: Long,
)

data class ManualOrderPlan(
    val assignments: List<ManualOrderAssignment>,
) {
    val channelIds: List<String>
        get() = assignments.map(ManualOrderAssignment::channelId)
}

enum class ManualOrderPlacement {
    BEFORE,
    AFTER,
}

enum class ManualOrderFailureReason {
    EMPTY_CHANNEL_ID,
    DUPLICATE_CHANNEL_ID,
    CHANNEL_NOT_FOUND,
    TARGET_INDEX_OUT_OF_RANGE,
    EMPTY_SELECTION,
    SELECTED_CHANNEL_NOT_FOUND,
}

sealed interface ManualOrderPlanResult {
    data class Success(val plan: ManualOrderPlan) : ManualOrderPlanResult

    data class Failure(
        val reason: ManualOrderFailureReason,
        val channelId: String? = null,
    ) : ManualOrderPlanResult
}

object ManualChannelOrderPlanner {
    fun move(
        currentOrder: List<String>,
        channelId: String,
        targetIndex: Int,
    ): ManualOrderPlanResult {
        validateCurrentOrder(currentOrder)?.let { return it }
        if (channelId.isBlank()) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.EMPTY_CHANNEL_ID,
            )
        }
        val currentIndex = currentOrder.indexOf(channelId)
        if (currentIndex == -1) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.CHANNEL_NOT_FOUND,
                channelId = channelId,
            )
        }
        if (targetIndex !in currentOrder.indices) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.TARGET_INDEX_OUT_OF_RANGE,
                channelId = channelId,
            )
        }

        val reordered = currentOrder.toMutableList()
        reordered.removeAt(currentIndex)
        reordered.add(targetIndex, channelId)
        return success(reordered)
    }

    fun moveRelative(
        currentOrder: List<String>,
        channelId: String,
        anchorChannelId: String,
        placement: ManualOrderPlacement,
    ): ManualOrderPlanResult {
        validateCurrentOrder(currentOrder)?.let { return it }
        if (channelId.isBlank() || anchorChannelId.isBlank()) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.EMPTY_CHANNEL_ID,
            )
        }
        val currentIndex = currentOrder.indexOf(channelId)
        if (currentIndex == -1) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.CHANNEL_NOT_FOUND,
                channelId = channelId,
            )
        }
        val anchorIndex = currentOrder.indexOf(anchorChannelId)
        if (anchorIndex == -1) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.CHANNEL_NOT_FOUND,
                channelId = anchorChannelId,
            )
        }
        if (channelId == anchorChannelId) return success(currentOrder)

        val targetIndex = when (placement) {
            ManualOrderPlacement.BEFORE -> if (currentIndex < anchorIndex) {
                anchorIndex - 1
            } else {
                anchorIndex
            }

            ManualOrderPlacement.AFTER -> if (currentIndex < anchorIndex) {
                anchorIndex
            } else {
                anchorIndex + 1
            }
        }
        return move(
            currentOrder = currentOrder,
            channelId = channelId,
            targetIndex = targetIndex,
        )
    }

    fun moveSelectedToTop(
        currentOrder: List<String>,
        selectedChannelIds: Set<String>,
    ): ManualOrderPlanResult = moveSelection(
        currentOrder = currentOrder,
        selectedChannelIds = selectedChannelIds,
        toTop = true,
    )

    fun moveSelectedToBottom(
        currentOrder: List<String>,
        selectedChannelIds: Set<String>,
    ): ManualOrderPlanResult = moveSelection(
        currentOrder = currentOrder,
        selectedChannelIds = selectedChannelIds,
        toTop = false,
    )

    private fun moveSelection(
        currentOrder: List<String>,
        selectedChannelIds: Set<String>,
        toTop: Boolean,
    ): ManualOrderPlanResult {
        validateCurrentOrder(currentOrder)?.let { return it }
        if (selectedChannelIds.isEmpty()) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.EMPTY_SELECTION,
            )
        }

        val blankSelected = selectedChannelIds.firstOrNull(String::isBlank)
        if (blankSelected != null) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.EMPTY_CHANNEL_ID,
            )
        }

        val currentIds = currentOrder.toHashSet()
        val missing = selectedChannelIds.firstOrNull { channelId -> channelId !in currentIds }
        if (missing != null) {
            return ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.SELECTED_CHANNEL_NOT_FOUND,
                channelId = missing,
            )
        }

        val selectedInCurrentOrder = currentOrder.filter(selectedChannelIds::contains)
        val unselectedInCurrentOrder = currentOrder.filterNot(selectedChannelIds::contains)
        val reordered = if (toTop) {
            selectedInCurrentOrder + unselectedInCurrentOrder
        } else {
            unselectedInCurrentOrder + selectedInCurrentOrder
        }
        return success(reordered)
    }

    private fun validateCurrentOrder(currentOrder: List<String>): ManualOrderPlanResult.Failure? {
        val seen = hashSetOf<String>()
        currentOrder.forEach { channelId ->
            if (channelId.isBlank()) {
                return ManualOrderPlanResult.Failure(
                    reason = ManualOrderFailureReason.EMPTY_CHANNEL_ID,
                )
            }
            if (!seen.add(channelId)) {
                return ManualOrderPlanResult.Failure(
                    reason = ManualOrderFailureReason.DUPLICATE_CHANNEL_ID,
                    channelId = channelId,
                )
            }
        }
        return null
    }

    private fun success(channelIds: List<String>): ManualOrderPlanResult.Success =
        ManualOrderPlanResult.Success(
            ManualOrderPlan(
                assignments = channelIds.mapIndexed { index, channelId ->
                    ManualOrderAssignment(
                        channelId = channelId,
                        manualOrder = index.toLong(),
                    )
                },
            ),
        )
}
