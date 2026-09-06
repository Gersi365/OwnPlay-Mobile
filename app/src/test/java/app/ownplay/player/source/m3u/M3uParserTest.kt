package app.ownplay.player.source.m3u

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    @Test
    fun parsesCommonMetadataAndQuotedComma() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U url-tvg="https://example.com/guide.xml"
            #EXTINF:-1 tvg-id="news.1" tvg-name="News One" tvg-logo="https://img.example/logo.png" group-title="News, Local",News One HD
            #KODIPROP:inputstream.adaptive.manifest_type=hls
            https://stream.example/live/1.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("https://example.com/guide.xml"), playlist.epgUrls)
        assertEquals(1, playlist.entries.size)
        val entry = playlist.entries.single()
        assertEquals("News One HD", entry.displayName)
        assertEquals("https://stream.example/live/1.m3u8", entry.streamUrl)
        assertEquals("news.1", entry.tvgId)
        assertEquals("News One", entry.tvgName)
        assertEquals("https://img.example/logo.png", entry.logoUrl)
        assertEquals("News, Local", entry.groupTitle)
    }

    @Test
    fun attributesAreTrimmedAndCaseNormalizedIndependentOfDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val playlist = M3uParser.parse(
                """
                #EXTM3U URL-TVG=" https://example.com/guide.xml "
                #EXTINF:-1 TVG-ID=" news.1 " TVG-NAME=" News One " GROUP-TITLE=" News ",
                https://stream.example/live/1
                """.trimIndent(),
            )

            assertEquals(listOf("https://example.com/guide.xml"), playlist.epgUrls)
            val entry = playlist.entries.single()
            assertEquals("news.1", entry.tvgId)
            assertEquals("News One", entry.tvgName)
            assertEquals("News One", entry.displayName)
            assertEquals("News", entry.groupTitle)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun malformedExtInfWithoutComma_usesTvgNameFallback() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="Fallback Name" group-title='Misc'
            #EXTVLCOPT:http-user-agent=OwnPlay-Test
            https://stream.example/live/fallback
            """.trimIndent(),
        )

        val entry = playlist.entries.single()
        assertEquals("Fallback Name", entry.displayName)
        assertEquals("Misc", entry.groupTitle)
    }

    @Test
    fun standaloneStreamUrl_isRetainedWithDerivedName() {
        val playlist = M3uParser.parse("https://stream.example/live/channel-7.m3u8")

        val entry = playlist.entries.single()
        assertEquals("channel-7.m3u8", entry.displayName)
        assertEquals("https://stream.example/live/channel-7.m3u8", entry.streamUrl)
        assertNull(entry.tvgId)
    }

    @Test
    fun missingStreamForPreviousExtInf_doesNotLeakMetadataToNextEntry() {
        val playlist = M3uParser.parse(
            """
            #EXTINF:-1 tvg-name="Missing",Missing
            #EXTINF:-1 tvg-name="Present",Present
            https://stream.example/present
            """.trimIndent(),
        )

        assertEquals(1, playlist.entries.size)
        assertEquals("Present", playlist.entries.single().displayName)
    }

    @Test
    fun headerCollectsDistinctEpgUrls() {
        val playlist = M3uParser.parse(
            """
            #EXTM3U url-tvg="https://example.com/a.xml, https://example.com/b.xml" x-tvg-url="https://example.com/a.xml"
            https://stream.example/one
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://example.com/a.xml", "https://example.com/b.xml"),
            playlist.epgUrls,
        )
    }

    @Test
    fun utf8BomBeforeHeader_doesNotCreateBogusEntry() {
        val playlist = M3uParser.parse(
            "\uFEFF#EXTM3U\n#EXTINF:-1 tvg-name=\"BOM Channel\",BOM Channel\nhttps://stream.example/bom",
        )

        assertEquals(1, playlist.entries.size)
        assertEquals("BOM Channel", playlist.entries.single().displayName)
        assertEquals("https://stream.example/bom", playlist.entries.single().streamUrl)
    }

    @Test
    fun htmlLikeGarbage_doesNotBecomeStreamEntries() {
        val playlist = M3uParser.parse(
            """
            <!doctype html>
            <html>
            <body>Not a playlist</body>
            </html>
            """.trimIndent(),
        )

        assertEquals(0, playlist.entries.size)
    }
}
