package app.ownplay.mobile.sources.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceLocatorPolicyTest {
    @Test
    fun `connection label removes user info query and fragment`() {
        assertEquals(
            "https://example.com:8443/path",
            SourceLocatorPolicy.connectionLabel(
                "https://user:secret@example.com:8443/path?token=secret#fragment",
            ),
        )
    }

    @Test
    fun `invalid locator never echoes original input`() {
        assertEquals(
            "Configured source",
            SourceLocatorPolicy.connectionLabel("not a valid url with secret"),
        )
    }
}
