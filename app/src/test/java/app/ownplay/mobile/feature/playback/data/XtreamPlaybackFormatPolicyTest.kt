package app.ownplay.mobile.feature.playback.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamPlaybackFormatPolicyTest {
    @Test
    fun tsFallsBackToHls() {
        assertEquals("m3u8", XtreamPlaybackFormatPolicy.fallbackExtension("ts"))
        assertEquals(
            XtreamPlaybackFormatPolicy.HLS_MIME_TYPE,
            XtreamPlaybackFormatPolicy.mimeType("m3u8"),
        )
    }

    @Test
    fun hlsFallsBackToTs() {
        assertEquals("ts", XtreamPlaybackFormatPolicy.fallbackExtension("m3u8"))
        assertNull(XtreamPlaybackFormatPolicy.mimeType("ts"))
    }

    @Test
    fun unknownFormatsDoNotInventFallback() {
        assertNull(XtreamPlaybackFormatPolicy.fallbackExtension("mp4"))
        assertNull(XtreamPlaybackFormatPolicy.mimeType("mp4"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals("ts", XtreamPlaybackFormatPolicy.fallbackExtension("M3U8"))
        assertEquals(
            XtreamPlaybackFormatPolicy.HLS_MIME_TYPE,
            XtreamPlaybackFormatPolicy.mimeType("M3U8"),
        )
    }
}
