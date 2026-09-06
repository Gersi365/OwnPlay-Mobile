package app.ownplay.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackInteractionBridgeTest {
    @Test
    fun registeredBackActionIsConsumedUntilOwnerClearsIt() {
        val owner = Any()
        var invoked = false

        PlaybackInteractionBridge.registerBackAction(owner) {
            invoked = true
        }

        assertTrue(PlaybackInteractionBridge.handleBack())
        assertTrue(invoked)

        PlaybackInteractionBridge.clearBackAction(owner)
        assertFalse(PlaybackInteractionBridge.handleBack())
    }

    @Test
    fun staleOwnerCannotClearNewerBackAction() {
        val firstOwner = Any()
        val secondOwner = Any()
        var firstInvocations = 0
        var secondInvocations = 0

        PlaybackInteractionBridge.registerBackAction(firstOwner) {
            firstInvocations += 1
        }
        PlaybackInteractionBridge.registerBackAction(secondOwner) {
            secondInvocations += 1
        }

        PlaybackInteractionBridge.clearBackAction(firstOwner)

        assertTrue(PlaybackInteractionBridge.handleBack())
        assertEquals(0, firstInvocations)
        assertEquals(1, secondInvocations)

        PlaybackInteractionBridge.clearBackAction(secondOwner)
        assertFalse(PlaybackInteractionBridge.handleBack())
    }
}
