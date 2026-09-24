package app.ownplay.mobile.sources.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamEpgTextPolicyTest {
    @Test
    fun decodesBase64AndHtmlEntitiesWithoutCorruptingPlainText() {
        assertEquals("News & Weather", XtreamEpgTextPolicy.decode("TmV3cyAmYW1wOyBXZWF0aGVy"))
        assertEquals("Movie & News", XtreamEpgTextPolicy.decode("Movie &amp; News"))
        assertEquals("BBC One", XtreamEpgTextPolicy.decode("BBC One"))
    }
}
