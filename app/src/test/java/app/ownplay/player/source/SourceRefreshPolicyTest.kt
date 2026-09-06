package app.ownplay.player.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRefreshPolicyTest {
    @Test
    fun missingSuccess_isStale() {
        assertTrue(shouldRefreshSource(null, nowEpochMillis = 100L, staleAfterMillis = 50L))
    }

    @Test
    fun recentSuccess_isNotStale() {
        assertFalse(shouldRefreshSource(80L, nowEpochMillis = 100L, staleAfterMillis = 50L))
    }

    @Test
    fun oldSuccess_isStale() {
        assertTrue(shouldRefreshSource(50L, nowEpochMillis = 100L, staleAfterMillis = 50L))
    }

    @Test
    fun clockRollbackTreatsPersistedSuccessAsStale() {
        assertTrue(shouldRefreshSource(120L, nowEpochMillis = 100L, staleAfterMillis = 50L))
    }
}
