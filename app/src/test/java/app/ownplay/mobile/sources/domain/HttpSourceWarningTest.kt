package app.ownplay.mobile.sources.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSourceWarningTest {
    @Test fun httpWarningNeverEchoesSyntheticCredentials() {
        val message = SourceConnectionSecurityPolicy.transportWarning("http://example.test/live/synthetic-user/synthetic-password/1.ts")!!
        assertTrue(message.contains("HTTPS"))
        assertFalse(message.contains("synthetic-user"))
        assertFalse(message.contains("synthetic-password"))
        assertNotNull(SourceConnectionSecurityPolicy.transportWarning(" HTTP://example.test/list?password=synthetic-secret "))
    }

    @Test fun httpsHasNoCleartextWarningAndHttpCompatibilityRemains() {
        assertNull(SourceConnectionSecurityPolicy.transportWarning("https://example.test"))
        assertTrue(SourceConnectionSecurityPolicy.normalizeXtreamBaseUrl("http://example.test") is ConnectionValidation.Valid)
    }
}
