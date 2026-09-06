package app.ownplay.player.source.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamSourceLocatorTest {
    @Test
    fun encodedLocatorRoundTripsCleartextOptInWithoutRenderingServer() {
        val serverUrl = "http://provider.example.test/base/"
        val encoded = XtreamSourceLocatorCodec.encode(
            XtreamSourceLocator(
                serverUrl = serverUrl,
                allowCleartext = true,
            ),
        )

        val parsed = XtreamSourceLocatorCodec.parse(encoded)

        assertEquals(serverUrl, parsed?.serverUrl)
        assertEquals(true, parsed?.allowCleartext)
        assertFalse(parsed.toString().contains("provider.example.test"))
        assertTrue(parsed.toString().contains("allowCleartext=true"))
    }

    @Test
    fun legacyRawLocatorRemainsSupportedButDoesNotGainCleartextPermission() {
        val parsed = XtreamSourceLocatorCodec.parse("https://provider.example.test/")

        assertEquals("https://provider.example.test/", parsed?.serverUrl)
        assertEquals(false, parsed?.allowCleartext)
    }

    @Test
    fun malformedVersionedLocatorIsRejected() {
        assertNull(XtreamSourceLocatorCodec.parse("ownplay-xtream-source-v1|unknown|http://example.test/"))
        assertNull(XtreamSourceLocatorCodec.parse("ownplay-xtream-source-v1|secure|"))
    }
}
