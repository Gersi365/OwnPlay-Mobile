package app.ownplay.mobile.downloads.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPreferencesTest {
    @Test
    fun defaultsMatchProductContract() {
        val defaults = DownloadPreferences()
        assertFalse(defaults.wifiOnly)
        assertEquals("Download/OwnPlay Downloads/", defaults.destinationRelativePath)
        assertTrue(defaults.notificationsEnabled)
    }

    @Test
    fun destinationPolicyRejectsTraversalAndNormalizesDownloadRoot() {
        assertEquals(
            "Download/OwnPlay Downloads/",
            DownloadDestinationPolicy.normalize(" Download/OwnPlay Downloads "),
        )
        assertNull(DownloadDestinationPolicy.normalize("../OwnPlay"))
        assertNull(DownloadDestinationPolicy.normalize("Movies/OwnPlay"))
    }
}
