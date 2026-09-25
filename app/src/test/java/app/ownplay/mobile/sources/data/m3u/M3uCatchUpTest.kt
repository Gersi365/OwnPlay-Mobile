package app.ownplay.mobile.sources.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uCatchUpTest {
    @Test
    fun `renders explicit provider catch up template`() {
        val entry = entry(
            source = "https://archive.example.com/live/{utc}/{utcend}/{duration}.ts?token=opaque",
        )

        val url = M3uCatchUpTemplate.render(entry, 1_000L, 1_300L)

        assertEquals(
            "https://archive.example.com/live/1000/1300/300.ts?token=opaque",
            url,
        )
    }

    @Test
    fun `rejects disabled or non-templated catch up entries`() {
        assertTrue(M3uCatchUpTemplate.supports(entry(source = "https://archive.example.com/{utc}.ts")))
        assertNull(M3uCatchUpTemplate.render(entry(source = "https://archive.example.com/static.ts"), 1_000L, 1_300L))
        assertNull(
            M3uCatchUpTemplate.render(
                entry(source = "https://archive.example.com/{utc}.ts", mode = "off"),
                1_000L,
                1_300L,
            ),
        )
    }

    private fun entry(source: String, mode: String? = "default") = M3uEntry(
        name = "News",
        groupTitle = "News",
        tvgId = "news.al",
        tvgName = "News",
        logoUrl = null,
        streamUrl = "https://live.example.com/news.ts",
        catchUpMode = mode,
        catchUpDays = 7,
        catchUpSource = source,
    )
}
