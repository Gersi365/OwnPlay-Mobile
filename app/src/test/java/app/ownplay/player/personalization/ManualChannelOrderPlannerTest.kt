package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualChannelOrderPlannerTest {
    @Test
    fun movePlacesChannelAtFinalTargetIndexAndAssignsContiguousOrder() {
        val result = ManualChannelOrderPlanner.move(
            currentOrder = listOf("one", "two", "three", "four"),
            channelId = "two",
            targetIndex = 3,
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("one", "three", "four", "two"), success.plan.channelIds)
        assertEquals(listOf(0L, 1L, 2L, 3L), success.plan.assignments.map { it.manualOrder })
    }

    @Test
    fun moveToSameIndexIsDeterministic() {
        val result = ManualChannelOrderPlanner.move(
            currentOrder = listOf("one", "two", "three"),
            channelId = "two",
            targetIndex = 1,
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("one", "two", "three"), success.plan.channelIds)
    }

    @Test
    fun moveRelativeAfterUsesAnchorIdentityAcrossIndexShift() {
        val result = ManualChannelOrderPlanner.moveRelative(
            currentOrder = listOf("one", "two", "three", "four"),
            channelId = "one",
            anchorChannelId = "three",
            placement = ManualOrderPlacement.AFTER,
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("two", "three", "one", "four"), success.plan.channelIds)
    }

    @Test
    fun moveRelativeBeforeUsesAnchorIdentityAcrossIndexShift() {
        val result = ManualChannelOrderPlanner.moveRelative(
            currentOrder = listOf("one", "two", "three", "four"),
            channelId = "four",
            anchorChannelId = "two",
            placement = ManualOrderPlacement.BEFORE,
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("one", "four", "two", "three"), success.plan.channelIds)
    }

    @Test
    fun moveRelativeToSelfIsNoOp() {
        val result = ManualChannelOrderPlanner.moveRelative(
            currentOrder = listOf("one", "two", "three"),
            channelId = "two",
            anchorChannelId = "two",
            placement = ManualOrderPlacement.AFTER,
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("one", "two", "three"), success.plan.channelIds)
    }

    @Test
    fun moveRelativeMissingAnchorFailsExplicitly() {
        val result = ManualChannelOrderPlanner.moveRelative(
            currentOrder = listOf("one", "two", "three"),
            channelId = "one",
            anchorChannelId = "missing",
            placement = ManualOrderPlacement.BEFORE,
        )

        assertEquals(
            ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.CHANNEL_NOT_FOUND,
                channelId = "missing",
            ),
            result,
        )
    }

    @Test
    fun moveSelectedToTopPreservesRelativeOrderFromCurrentList() {
        val result = ManualChannelOrderPlanner.moveSelectedToTop(
            currentOrder = listOf("one", "two", "three", "four", "five"),
            selectedChannelIds = setOf("four", "two"),
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("two", "four", "one", "three", "five"), success.plan.channelIds)
    }

    @Test
    fun moveSelectedToBottomPreservesRelativeOrderFromCurrentList() {
        val result = ManualChannelOrderPlanner.moveSelectedToBottom(
            currentOrder = listOf("one", "two", "three", "four", "five"),
            selectedChannelIds = setOf("four", "two"),
        )

        val success = result as ManualOrderPlanResult.Success
        assertEquals(listOf("one", "three", "five", "two", "four"), success.plan.channelIds)
    }

    @Test
    fun duplicateCurrentIdsFailInsteadOfProducingAmbiguousOrder() {
        val result = ManualChannelOrderPlanner.move(
            currentOrder = listOf("one", "two", "two"),
            channelId = "one",
            targetIndex = 1,
        )

        assertEquals(
            ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.DUPLICATE_CHANNEL_ID,
                channelId = "two",
            ),
            result,
        )
    }

    @Test
    fun missingDraggedChannelFailsExplicitly() {
        val result = ManualChannelOrderPlanner.move(
            currentOrder = listOf("one", "two"),
            channelId = "missing",
            targetIndex = 0,
        )

        assertEquals(
            ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.CHANNEL_NOT_FOUND,
                channelId = "missing",
            ),
            result,
        )
    }

    @Test
    fun invalidTargetIndexFailsExplicitly() {
        val result = ManualChannelOrderPlanner.move(
            currentOrder = listOf("one", "two"),
            channelId = "one",
            targetIndex = 2,
        )

        assertEquals(
            ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.TARGET_INDEX_OUT_OF_RANGE,
                channelId = "one",
            ),
            result,
        )
    }

    @Test
    fun emptyBulkSelectionFailsExplicitly() {
        val result = ManualChannelOrderPlanner.moveSelectedToTop(
            currentOrder = listOf("one", "two"),
            selectedChannelIds = emptySet(),
        )

        assertEquals(
            ManualOrderPlanResult.Failure(
                reason = ManualOrderFailureReason.EMPTY_SELECTION,
            ),
            result,
        )
    }

    @Test
    fun unknownBulkSelectionFailsWithoutDroppingKnownChannels() {
        val result = ManualChannelOrderPlanner.moveSelectedToBottom(
            currentOrder = listOf("one", "two", "three"),
            selectedChannelIds = setOf("two", "missing"),
        )

        val failure = result as ManualOrderPlanResult.Failure
        assertEquals(ManualOrderFailureReason.SELECTED_CHANNEL_NOT_FOUND, failure.reason)
        assertEquals("missing", failure.channelId)
        assertTrue(result !is ManualOrderPlanResult.Success)
    }
}
