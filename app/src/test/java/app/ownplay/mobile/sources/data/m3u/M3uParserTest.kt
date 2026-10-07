package app.ownplay.mobile.sources.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserTest {
    @Test
    fun `parses common extinf metadata and preserves provider group`() {
        val result = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.al" tvg-name="News Albania" tvg-logo="https://img/logo.png" group-title="Albania",News Albania HD
            https://stream.example.com/live/1.m3u8?token=secret
            """.trimIndent(),
        )

        assertEquals(1, result.entries.size)
        assertEquals(0, result.skippedEntries)
        assertEquals("news.al", result.entries.single().tvgId)
        assertEquals("Albania", result.entries.single().groupTitle)
        assertEquals("News Albania HD", result.entries.single().name)
    }

    @Test
    fun `comma inside quoted attribute does not terminate metadata`() {
        val result = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="One, Two" group-title="Music",Channel Name
            https://stream.example.com/1.ts
            """.trimIndent(),
        )

        assertEquals(1, result.entries.size)
        assertEquals("One, Two", result.entries.single().tvgName)
        assertEquals("Channel Name", result.entries.single().name)
    }

    @Test
    fun `orphan stream line is counted as skipped`() {
        val result = M3uParser.parse(
            """
            #EXTM3U
            https://stream.example.com/orphan.ts
            """.trimIndent(),
        )

        assertEquals(0, result.entries.size)
        assertEquals(1, result.skippedEntries)
    }

    @Test
    fun `parses provider catch up metadata`() {
        val result = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.al" catchup="default" catchup-days="7" catchup-source="https://archive.example.com/{utc}/{duration}.ts",News
            https://stream.example.com/live.ts
            """.trimIndent(),
        )

        val entry = result.entries.single()
        assertEquals("default", entry.catchUpMode)
        assertEquals(7, entry.catchUpDays)
        assertEquals("https://archive.example.com/{utc}/{duration}.ts", entry.catchUpSource)
    }

    @Test
    fun `skips stream schemes without configured playback modules`() {
        val result = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,RTSP Channel
            rtsp://stream.example.com/live
            #EXTINF:-1,RTMP Channel
            rtmp://stream.example.com/live
            """.trimIndent(),
        )

        assertEquals(0, result.entries.size)
        assertEquals(2, result.skippedEntries)
    }
}