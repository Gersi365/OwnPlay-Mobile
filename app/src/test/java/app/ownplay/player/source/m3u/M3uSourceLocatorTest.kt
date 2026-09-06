package app.ownplay.player.source.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uSourceLocatorTest {
    @Test
    fun roundTrip_preservesEndpointHttpOptInAndDistinctEpgUrls() {
        val locator = M3uSourceLocator(
            endpoint = "http://provider.example/get.php?username=a%2Bb&password=x|y&type=m3u_plus",
            allowCleartext = true,
            epgUrls = listOf(
                "https://epg.example/guide.xml?region=a,b",
                " https://epg.example/guide.xml?region=a,b ",
                "http://epg.example/guide.xml.gz?token=x|y",
            ),
        )

        val decoded = M3uSourceLocatorCodec.parse(M3uSourceLocatorCodec.encode(locator))

        requireNotNull(decoded)
        assertEquals(locator.endpoint, decoded.endpoint)
        assertTrue(decoded.allowCleartext)
        assertEquals(
            listOf(
                "https://epg.example/guide.xml?region=a,b",
                "http://epg.example/guide.xml.gz?token=x|y",
            ),
            decoded.epgUrls,
        )
    }

    @Test
    fun locatorRenderingRedactsEndpointAndEpgUrls() {
        val endpointSecret = "playlist-secret-token"
        val epgSecret = "epg-secret-token"
        val locator = M3uSourceLocator(
            endpoint = "https://provider.example/playlist.m3u?token=$endpointSecret",
            allowCleartext = false,
            epgUrls = listOf("https://epg.example/guide.xml?token=$epgSecret"),
        )

        val rendered = locator.toString()

        assertFalse(rendered.contains(endpointSecret))
        assertFalse(rendered.contains(epgSecret))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun parseOrLegacy_keepsExistingRawLocatorWithoutMigration() {
        val legacy = "https://provider.example/playlist.m3u?token=abc"

        val decoded = M3uSourceLocatorCodec.parseOrLegacy(legacy)

        assertEquals(legacy, decoded.endpoint)
        assertFalse(decoded.allowCleartext)
        assertTrue(decoded.epgUrls.isEmpty())
    }

    @Test
    fun malformedVersionedValue_isTreatedAsLegacyByCompatibilityPath() {
        val malformed = "ownplay-m3u-v1|broken"

        assertEquals(null, M3uSourceLocatorCodec.parse(malformed))
        assertEquals(malformed, M3uSourceLocatorCodec.parseOrLegacy(malformed).endpoint)
    }
}
