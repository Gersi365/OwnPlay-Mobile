package app.ownplay.mobile.feature.live.domain

import app.ownplay.mobile.sources.data.m3u.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SanitizedLiveFixtureTest {
    @Test
    fun sanitizedFixturePreservesProviderGroupsMembershipAndOrder() {
        val content = checkNotNull(javaClass.classLoader?.getResource(
            "fixtures/sanitized/live_multicountry.m3u",
        )).readText()
        val parsed = M3uParser.parse(content)

        assertEquals(6, parsed.entries.size)
        assertEquals(0, parsed.skippedEntries)
        assertFalse(content.contains("token=", ignoreCase = true))
        assertFalse(content.contains("password", ignoreCase = true))

        assertEquals(
            listOf(
                "Shqipëri Lajme",
                "Italia Cinema",
                "Deutschland Sport",
                "Sverige Barn",
                "Premium Mix",
            ),
            parsed.entries.mapNotNull { it.groupTitle }.distinct(),
        )
        assertEquals(
            listOf(
                "al-news-1",
                "it-movie-1",
                "de-sport-1",
                "se-kids-1",
                "unknown-1",
                "unknown-2",
            ),
            parsed.entries.map { it.tvgId },
        )
        assertEquals(
            listOf("unknown-1", "unknown-2"),
            parsed.entries
                .filter { it.groupTitle == "Premium Mix" }
                .map { it.tvgId },
        )
    }
}
