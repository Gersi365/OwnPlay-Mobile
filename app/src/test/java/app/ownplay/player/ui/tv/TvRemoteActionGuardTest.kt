package app.ownplay.player.ui.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteActionGuardTest {
    @Test
    fun standardActionsRejectRapidRepeatedActivationForSameInput() {
        val guard = TvRemoteActionGuard()

        assertTrue(guard.tryAcquire(1_000L, actionId = 23))
        assertFalse(guard.tryAcquire(1_399L, actionId = 23))
        assertTrue(guard.tryAcquire(1_400L, actionId = 23))
    }

    @Test
    fun differentStandardInputsDoNotBlockSafeEscapeActions() {
        val guard = TvRemoteActionGuard()

        assertTrue(guard.tryAcquire(1_000L, actionId = 23))
        assertTrue(guard.tryAcquire(1_050L, actionId = 4))
        assertFalse(guard.tryAcquire(1_100L, actionId = 23))
        assertFalse(guard.tryAcquire(1_100L, actionId = 4))
    }

    @Test
    fun transitionActionsHoldLongerLockAcrossDifferentCommands() {
        val guard = TvRemoteActionGuard()

        assertTrue(
            guard.tryAcquire(
                nowMillis = 2_000L,
                actionId = 23,
                kind = TvRemoteActionKind.TRANSITION,
            ),
        )
        assertFalse(guard.tryAcquire(2_899L, actionId = 4))
        assertTrue(guard.tryAcquire(2_900L, actionId = 4))
    }

    @Test
    fun extendingTransitionLockOverridesShorterExistingDeadline() {
        val guard = TvRemoteActionGuard()

        assertTrue(guard.tryAcquire(3_000L, actionId = 23))
        guard.extendBlock(3_050L, kind = TvRemoteActionKind.TRANSITION)

        assertFalse(guard.tryAcquire(3_949L, actionId = 4))
        assertTrue(guard.tryAcquire(3_950L, actionId = 4))
    }

    @Test
    fun extendingBlockNeverShortensExistingDeadline() {
        val guard = TvRemoteActionGuard()

        assertTrue(
            guard.tryAcquire(
                nowMillis = 4_000L,
                actionId = 23,
                kind = TvRemoteActionKind.TRANSITION,
            ),
        )
        guard.extendBlock(4_050L, kind = TvRemoteActionKind.STANDARD)

        assertFalse(guard.tryAcquire(4_899L, actionId = 4))
        assertTrue(guard.tryAcquire(4_900L, actionId = 4))
    }

    @Test
    fun transitionStateIsVisibleForReleaseSuppressionUntilBoundary() {
        val guard = TvRemoteActionGuard()

        guard.extendBlock(5_000L, kind = TvRemoteActionKind.TRANSITION)

        assertTrue(guard.isGloballyBlocked(5_899L))
        assertFalse(guard.isGloballyBlocked(5_900L))
    }

    @Test
    fun lockDeadlineSaturatesInsteadOfOverflowing() {
        val guard = TvRemoteActionGuard()

        assertTrue(
            guard.tryAcquire(
                nowMillis = Long.MAX_VALUE - 100L,
                actionId = 23,
                kind = TvRemoteActionKind.TRANSITION,
            ),
        )
        assertFalse(guard.tryAcquire(Long.MAX_VALUE - 1L, actionId = 4))
    }
}
