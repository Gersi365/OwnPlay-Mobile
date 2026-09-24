package app.ownplay.mobile.data

import app.ownplay.mobile.data.db.ChannelPersonalizationEntity
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceContractTest {
    @Test
    fun sourceAndProviderRowsKeepSecretsAndPersonalizationOutOfImportedState() {
        val sourceFields = SourceEntity::class.java.declaredFields.map { it.name.lowercase() }.toSet()
        val providerFields = LiveChannelEntity::class.java.declaredFields.map { it.name.lowercase() }.toSet()
        val personalizationFields =
            ChannelPersonalizationEntity::class.java.declaredFields.map { it.name.lowercase() }.toSet()

        assertFalse("username" in sourceFields)
        assertFalse("password" in sourceFields)
        assertFalse("token" in sourceFields)
        assertFalse("authorization" in sourceFields)

        assertFalse("favorite" in providerFields)
        assertFalse("hidden" in providerFields)
        assertFalse("localname" in providerFields)
        assertFalse("manualorder" in providerFields)

        assertTrue("favorite" in personalizationFields)
        assertTrue("hidden" in personalizationFields)
        assertTrue("localname" in personalizationFields)
        assertTrue("manualorder" in personalizationFields)
    }

    @Test
    fun durablePreferenceDefaultsMatchProductContract() {
        val display = DisplayPreferences()
        assertFalse(display.compactMediaRows)
        assertTrue(display.showChannelLogos)
        assertFalse(display.preferTvgName)
        assertTrue(display.hideChannelPrefix)

        val playback = PlaybackPreferences()
        assertTrue(playback.automaticPictureInPicture)
        assertEquals(1f, playback.playerVolume)

        val downloads = DownloadPreferences()
        assertEquals(DownloadDestinationPolicy.DEFAULT_DESTINATION, downloads.destinationRelativePath)
        assertTrue(downloads.notificationsEnabled)
    }
}
