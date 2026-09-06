package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelEditReducerTest {
    @Test
    fun enterStartsWithEmptySelection() {
        val state = ChannelEditReducer.enter(
            ChannelEditState(
                isEditing = false,
                selectedChannelIds = setOf("stale"),
            ),
        )

        assertTrue(state.isEditing)
        assertTrue(state.selectedChannelIds.isEmpty())
    }

    @Test
    fun toggleAddsAndRemovesSelectionOnlyInEditMode() {
        var state = ChannelEditReducer.enter(ChannelEditState())
        state = ChannelEditReducer.toggleSelection(state, "one")
        assertEquals(setOf("one"), state.selectedChannelIds)

        state = ChannelEditReducer.toggleSelection(state, "one")
        assertTrue(state.selectedChannelIds.isEmpty())

        val browsing = ChannelEditReducer.toggleSelection(ChannelEditState(), "one")
        assertTrue(browsing.selectedChannelIds.isEmpty())
    }

    @Test
    fun selectVisibleUsesOnlyValidUniqueVisibleIds() {
        val state = ChannelEditReducer.selectVisible(
            state = ChannelEditReducer.enter(ChannelEditState()),
            visibleChannelIds = listOf("one", "", "two", "one"),
        )

        assertEquals(linkedSetOf("one", "two"), state.selectedChannelIds)
    }

    @Test
    fun retainAvailableDropsSelectionRemovedByDataRefresh() {
        val editing = ChannelEditState(
            isEditing = true,
            selectedChannelIds = linkedSetOf("one", "two", "three"),
        )

        val state = ChannelEditReducer.retainAvailable(
            state = editing,
            availableChannelIds = listOf("one", "three", "four"),
        )

        assertEquals(linkedSetOf("one", "three"), state.selectedChannelIds)
    }

    @Test
    fun exitClearsSelectionAndLeavesEditMode() {
        val state = ChannelEditReducer.exit(
            ChannelEditState(
                isEditing = true,
                selectedChannelIds = setOf("one", "two"),
            ),
        )

        assertFalse(state.isEditing)
        assertTrue(state.selectedChannelIds.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun groupBulkActionRejectsBlankGroupId() {
        ChannelBulkAction.AddToGroup(" ")
    }
}
