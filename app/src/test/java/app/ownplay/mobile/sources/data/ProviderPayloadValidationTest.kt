package app.ownplay.mobile.sources.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPayloadValidationTest {
    @Test
    fun `html login page is rejected even with success http status`() {
        assertEquals(
            ProviderPayloadValidationResult.Invalid(
                ProviderPayloadRejection.HTML_RESPONSE,
            ),
            ProviderPayloadValidation.validate(
                "<html><body>Login</body></html>",
                ProviderPayloadKind.JSON,
            ),
        )
    }

    @Test
    fun `json array is accepted`() {
        assertEquals(
            ProviderPayloadValidationResult.Valid,
            ProviderPayloadValidation.validate(
                "[ { \"stream_id\": 1 } ]",
                ProviderPayloadKind.JSON,
            ),
        )
    }

    @Test
    fun `m3u requires playlist markers`() {
        assertEquals(
            ProviderPayloadValidationResult.Invalid(
                ProviderPayloadRejection.UNEXPECTED_FORMAT,
            ),
            ProviderPayloadValidation.validate(
                "plain provider error text",
                ProviderPayloadKind.M3U,
            ),
        )
    }
}
