package app.ownplay.mobile.feature.settings.domain

import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRefreshRetryPolicyTest {
    @Test
    fun retriesOnlyTransientNetworkFailures() {
        assertTrue(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.NETWORK))
        assertTrue(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.TIMEOUT))
        assertTrue(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.TRANSIENT_PROVIDER))
        assertFalse(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.AUTHENTICATION))
        assertFalse(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.INVALID_PAYLOAD))
        assertFalse(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.PROVIDER))
        assertFalse(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.STORAGE))
        assertFalse(SourceRefreshRetryPolicy.shouldRetry(SourceRefreshFailureCategory.UNKNOWN))
    }
}
