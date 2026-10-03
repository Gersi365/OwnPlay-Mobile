package app.ownplay.mobile.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderRefreshCompatibilityTest {
    @Test fun membershipChangeKeepsDirtyEditsButRejectsTheirSave() {
        val dirty = OwnPlayReorderSession(listOf("visible", "hidden"), listOf("hidden", "visible"))
        val refreshed = dirty.syncCommitted(listOf("visible", "hidden", "new"))
        assertEquals(dirty.workingIds, refreshed.workingIds)
        assertFalse(refreshed.canCommitTo(listOf("visible", "hidden", "new")))
        assertFalse(refreshed.canCommitTo(listOf("visible")))
    }

    @Test fun stableMembershipIncludingHiddenRowsCanStillBeSaved() {
        val dirty = OwnPlayReorderSession(listOf("visible", "hidden"), listOf("hidden", "visible"))
        assertTrue(dirty.canCommitTo(listOf("hidden", "visible")))
        assertFalse(dirty.canCommitTo(listOf("visible", "visible")))
    }
}
