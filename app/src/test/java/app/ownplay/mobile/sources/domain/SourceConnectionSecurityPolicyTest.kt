package app.ownplay.mobile.sources.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceConnectionSecurityPolicyTest {
    @Test
    fun `xtream base url is normalized without trailing slash`() {
        val result = SourceConnectionSecurityPolicy.normalizeXtreamBaseUrl(
            " HTTPS://Example.COM/provider/ ",
        )

        assertEquals(
            ConnectionValidation.Valid("https://example.com/provider"),
            result,
        )
    }

    @Test
    fun `xtream base url rejects query credentials`() {
        val result = SourceConnectionSecurityPolicy.normalizeXtreamBaseUrl(
            "https://example.com?username=user&password=secret",
        )

        assertEquals(
            ConnectionValidation.Invalid(ConnectionRejection.EMBEDDED_SECRET_OR_QUERY),
            result,
        )
    }

    @Test
    fun `remote media url accepts secret query because it is stored encrypted`() {
        val result = SourceConnectionSecurityPolicy.normalizeRemoteMediaUrl(
            "https://example.com/list.m3u?token=secret",
        )

        assertTrue(result is ConnectionValidation.Valid)
    }
}
