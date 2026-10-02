package app.ownplay.mobile.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnPlayReorderSessionTest {
    @Test
    fun selectorActivatesOnlyOneItemAndSecondTapRequestsCommitAfterMove() {
        val activated = OwnPlayReorderSession(committedIds = listOf("a", "b", "c"))
            .onSelectorTap("b") as OwnPlayReorderSelectorAction.Updated<String>
        val moved = activated.session.moveActiveTo(0).finishDrag()

        assertEquals(listOf("b", "a", "c"), moved.workingIds)
        assertEquals(OwnPlayReorderPhase.PENDING, moved.phase)
        assertTrue(moved.isDirty)

        val action = moved.onSelectorTap("b") as OwnPlayReorderSelectorAction.CommitRequested<String>
        assertEquals(listOf("b", "a", "c"), action.orderedIds)
    }

    @Test
    fun noOpActivationAndDeactivationNeverRequestsSave() {
        val activated = OwnPlayReorderSession(committedIds = listOf("a", "b"))
            .onSelectorTap("a") as OwnPlayReorderSelectorAction.Updated<String>
        val deactivated = activated.session.onSelectorTap("a") as OwnPlayReorderSelectorAction.Updated<String>

        assertFalse(deactivated.session.isDirty)
        assertEquals(OwnPlayReorderPhase.FIXED, deactivated.session.phase)
        assertEquals(null, deactivated.session.activeId)
    }

    @Test
    fun dirtySessionRejectsSwitchingTheActiveSelector() {
        val session = ((OwnPlayReorderSession(committedIds = listOf("a", "b", "c"))
            .onSelectorTap("b") as OwnPlayReorderSelectorAction.Updated<String>)
            .session.moveActiveTo(0).finishDrag())

        val action = session.onSelectorTap("c") as OwnPlayReorderSelectorAction.Updated<String>

        assertEquals("b", action.session.activeId)
        assertEquals(listOf("b", "a", "c"), action.session.workingIds)
    }

    @Test
    fun discardRestoresCommittedOrderAndSavedSettlesBackToFixed() {
        val activated = OwnPlayReorderSession(committedIds = listOf("a", "b", "c"))
            .onSelectorTap("b") as OwnPlayReorderSelectorAction.Updated<String>
        val dirty = activated.session.moveActiveTo(0).finishDrag()

        assertEquals(listOf("a", "b", "c"), dirty.discard().workingIds)
        assertFalse(dirty.discard().isDirty)

        val saved = dirty.markSaved()
        assertEquals(OwnPlayReorderPhase.SAVED, saved.phase)
        assertEquals(OwnPlayReorderPhase.FIXED, saved.settleSaved().phase)
        assertFalse(saved.isDirty)
    }

    @Test
    fun subsetMergeChangesOnlyScopedSlots() {
        val merged = mergeReorderedSubset(
            fullOrder = listOf("al-1", "it-1", "al-2", "other"),
            scopedIds = listOf("al-1", "al-2"),
            reorderedScopedIds = listOf("al-2", "al-1"),
        )

        assertEquals(listOf("al-2", "it-1", "al-1", "other"), merged)
    }

    @Test
    fun invalidSubsetMergeIsSafeNoOp() {
        val original = listOf("a", "b", "c")

        assertEquals(
            original,
            mergeReorderedSubset(original, scopedIds = listOf("a", "b"), reorderedScopedIds = listOf("b")),
        )
    }
}
