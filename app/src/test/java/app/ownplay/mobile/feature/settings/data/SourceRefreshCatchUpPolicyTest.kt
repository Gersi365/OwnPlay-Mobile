package app.ownplay.mobile.feature.settings.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRefreshCatchUpPolicyTest {
    @Test
    fun `unset last success is due only when a schedule exists`() {
        assertTrue(SourceRefreshCatchUpPolicy.isDue(null, nowEpochMs, repeatHours = 12))
        assertFalse(SourceRefreshCatchUpPolicy.isDue(null, nowEpochMs, repeatHours = 0))
    }

    @Test
    fun `refresh is due only after configured freshness interval`() {
        val lastSuccess = nowEpochMs - 12L * 60L * 60L * 1_000L
        assertTrue(SourceRefreshCatchUpPolicy.isDue(lastSuccess, nowEpochMs, repeatHours = 12))
        assertFalse(SourceRefreshCatchUpPolicy.isDue(lastSuccess + 1L, nowEpochMs, repeatHours = 12))
        assertFalse(SourceRefreshCatchUpPolicy.isDue(nowEpochMs + 1L, nowEpochMs, repeatHours = 12))
    }

    private companion object {
        const val nowEpochMs = 1_800_000_000_000L
    }
}
