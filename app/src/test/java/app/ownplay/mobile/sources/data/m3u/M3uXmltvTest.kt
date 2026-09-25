package app.ownplay.mobile.sources.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uXmltvTest {
    @Test
    fun `parses provider channel programs and timezone offsets`() {
        val guide = M3uXmltvParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme channel="news.al" start="20260925090000 +0200" stop="20260925100000 +0200">
                <title lang="en">Morning News &amp; Weather</title>
              </programme>
              <programme channel="news.al" start="20260925100000 +0200" stop="20260925103000 +0200">
                <title>Headlines</title>
              </programme>
            </tv>
            """.trimIndent(),
        )

        val programs = guide.programsFor("news.al")
        assertEquals(2, programs.size)
        assertEquals("Morning News & Weather", programs[0].title)
        assertEquals(1_800L, programs[1].endEpochSeconds - programs[1].startEpochSeconds)
    }

    @Test
    fun `rejects xml with doctype or entity declarations`() {
        val guide = M3uXmltvParser.parse(
            """
            <!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <tv><programme channel="x" start="20260925090000 +0000" stop="20260925100000 +0000"><title>&xxe;</title></programme></tv>
            """.trimIndent(),
        )

        assertTrue(guide.programsFor("x").isEmpty())
    }
}
