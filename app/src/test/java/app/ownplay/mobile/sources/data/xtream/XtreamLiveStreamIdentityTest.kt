package app.ownplay.mobile.sources.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamLiveStreamIdentityTest {
    @Test
    fun preferredSupportedExtensionUsesProviderOrderWithoutGuessing() {
        assertEquals("m3u8", XtreamLiveStreamIdentity.preferredSupportedExtension(listOf("rtmp", "m3u8", "ts")))
        assertEquals("ts", XtreamLiveStreamIdentity.preferredSupportedExtension(listOf("TS")))
        assertNull(XtreamLiveStreamIdentity.preferredSupportedExtension(listOf("rtmp")))
    }

    @Test
    fun encodesAuthoritativeContainerExtensionWithoutGuessing() {
        val identity = XtreamLiveStreamIdentity.encode(
            streamId = "42",
            containerExtension = ".M3U8",
        )

        assertEquals("xtream://live/42?ext=m3u8", identity)
        assertEquals("m3u8", XtreamLiveStreamIdentity.extension(identity, "42"))
    }

    @Test
    fun missingOrUnsafeExtensionRemainsUnavailableForPlaybackPreparation() {
        assertEquals("xtream://live/42", XtreamLiveStreamIdentity.encode("42", null))
        assertEquals("xtream://live/42", XtreamLiveStreamIdentity.encode("42", "../ts"))
        assertNull(XtreamLiveStreamIdentity.extension("xtream://live/42", "42"))
        assertNull(XtreamLiveStreamIdentity.extension("xtream://live/42?ext=ts&token=x", "42"))
    }

    @Test
    fun extensionMustBelongToTheExpectedStreamIdentity() {
        val identity = XtreamLiveStreamIdentity.encode("42", "ts")

        assertNull(XtreamLiveStreamIdentity.extension(identity, "41"))
    }
}
