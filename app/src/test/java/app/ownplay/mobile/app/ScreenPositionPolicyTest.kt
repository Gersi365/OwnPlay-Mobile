package app.ownplay.mobile.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPositionPolicyTest {
    @Test fun settingsEntryAndBackAlwaysStartAtTop() {
        for (event in listOf(ScreenNavigationEvent.ENTER_PAGE, ScreenNavigationEvent.RETURN_TO_CONTEXT, ScreenNavigationEvent.CHANGE_CONTEXT, ScreenNavigationEvent.SOURCE_CHANGED)) {
            assertEquals(ScreenPositionAction.RESET_TOP, ScreenPositionPolicy.resolve(ScreenFamily.SETTINGS, event))
        }
    }

    @Test fun playerAndDetailReturnsKeepTheirBrowsingContextWithoutExpiry() {
        for (family in listOf(ScreenFamily.LIVE, ScreenFamily.LIBRARY, ScreenFamily.DOWNLOADS)) {
            assertEquals(ScreenPositionAction.RESTORE_CONTEXT, ScreenPositionPolicy.resolve(family, ScreenNavigationEvent.RETURN_TO_CONTEXT))
            assertEquals(ScreenPositionAction.RESET_TOP, ScreenPositionPolicy.resolve(family, ScreenNavigationEvent.CHANGE_CONTEXT))
            assertEquals(ScreenPositionAction.RESET_TOP, ScreenPositionPolicy.resolve(family, ScreenNavigationEvent.SOURCE_CHANGED))
            assertEquals(ScreenPositionAction.RESTORE_CONTEXT, ScreenPositionPolicy.resolve(family, ScreenNavigationEvent.REFRESH))
            assertEquals(ScreenPositionAction.RESTORE_CONTEXT, ScreenPositionPolicy.resolve(family, ScreenNavigationEvent.RESTORE_CONFIGURATION))
        }
    }

    @Test fun epgOpenAndChannelChangeAnchorCurrentInterval() {
        assertEquals(ScreenPositionAction.ANCHOR_CURRENT_EPG, ScreenPositionPolicy.resolve(ScreenFamily.EPG, ScreenNavigationEvent.OPEN_EPG))
        assertEquals(ScreenPositionAction.ANCHOR_CURRENT_EPG, ScreenPositionPolicy.resolve(ScreenFamily.EPG, ScreenNavigationEvent.CHANGE_CONTEXT))
        assertEquals(ScreenPositionAction.RESTORE_CONTEXT, ScreenPositionPolicy.resolve(ScreenFamily.EPG, ScreenNavigationEvent.REFRESH))
    }

    @Test fun dirtyTabExitWaitsForTheEditorsDecision() {
        val guard = SettingsExitGuard()
        val owner = Any()
        var pending: (() -> Unit)? = null
        var navigated = false
        guard.register(owner) { pending = it }
        guard.requestExit { navigated = true }
        assertFalse(navigated)
        pending!!.invoke()
        assertTrue(navigated)
    }

    @Test fun outgoingEditorCannotUnregisterTheCurrentEditor() {
        val guard = SettingsExitGuard()
        val oldOwner = Any()
        val newOwner = Any()
        var intercepted = false
        guard.register(oldOwner) { error("stale editor") }
        guard.register(newOwner) { intercepted = true }
        guard.unregister(oldOwner)
        guard.requestExit { error("unprotected exit") }
        assertTrue(intercepted)
        guard.unregister(newOwner)
        var navigated = false
        guard.requestExit { navigated = true }
        assertTrue(navigated)
    }
}
