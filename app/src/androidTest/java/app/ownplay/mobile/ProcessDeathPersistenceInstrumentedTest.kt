package app.ownplay.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.downloads.data.DataStoreDownloadPreferencesRepository
import app.ownplay.mobile.downloads.data.DownloadPreferencesDataStore
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.feature.playback.data.DataStorePlaybackPreferencesRepository
import app.ownplay.mobile.feature.playback.data.PlaybackPreferencesDataStore
import app.ownplay.mobile.feature.settings.data.DataStoreDisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.data.DisplayPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessDeathPersistenceInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aSeedDurableStateForProcessDeath() = runBlocking {
        val database = OwnPlayDatabase.create(context)
        val display = DataStoreDisplayPreferencesRepository(DisplayPreferencesDataStore(context))
        val playback = DataStorePlaybackPreferencesRepository(PlaybackPreferencesDataStore(context))
        val downloads = DataStoreDownloadPreferencesRepository(DownloadPreferencesDataStore(context))

        try {
            database.sourceDao().delete(PROCESS_SOURCE_ID)
            database.sourceDao().insert(
                SourceEntity(
                    sourceId = PROCESS_SOURCE_ID,
                    displayName = "Process death QA",
                    type = "M3U",
                    baseLocator = "https://example.invalid/process-death.m3u",
                    credentialReference = null,
                    enabled = true,
                    createdAt = 10L,
                    updatedAt = 10L,
                ),
            )
            assertTrue(display.setCompactMediaRows(true))
            assertTrue(display.setShowChannelLogos(false))
            assertTrue(display.setPreferTvgName(true))
            assertTrue(display.setHideChannelPrefix(false))
            assertTrue(playback.setAutomaticPictureInPicture(false))
            assertTrue(playback.setPlayerVolume(0.42f))
            assertTrue(downloads.setUnmeteredNetworkOnly(true))
            assertTrue(downloads.setDestinationRelativePath("Download/OwnPlay Process Death/"))
            assertTrue(downloads.setNotificationsEnabled(false))
        } finally {
            database.close()
        }
    }

    @Test
    fun bVerifyDurableStateAfterRelaunch() = runBlocking {
        val database = OwnPlayDatabase.create(context)
        val display = DataStoreDisplayPreferencesRepository(DisplayPreferencesDataStore(context))
        val playback = DataStorePlaybackPreferencesRepository(PlaybackPreferencesDataStore(context))
        val downloads = DataStoreDownloadPreferencesRepository(DownloadPreferencesDataStore(context))

        try {
            val source = database.sourceDao().get(PROCESS_SOURCE_ID)
            assertNotNull(source)
            assertEquals("Process death QA", source?.displayName)
            assertTrue(source?.enabled == true)

            val displayState = display.preferences.first()
            assertTrue(displayState.compactMediaRows)
            assertFalse(displayState.showChannelLogos)
            assertTrue(displayState.preferTvgName)
            assertFalse(displayState.hideChannelPrefix)

            val playbackState = playback.preferences.first()
            assertFalse(playbackState.automaticPictureInPicture)
            assertEquals(0.42f, playbackState.playerVolume)

            val downloadState = downloads.current()
            assertTrue(downloadState.wifiOnly)
            assertEquals("Download/OwnPlay Process Death/", downloadState.destinationRelativePath)
            assertFalse(downloadState.notificationsEnabled)
        } finally {
            database.sourceDao().delete(PROCESS_SOURCE_ID)
            database.close()
            display.setCompactMediaRows(false)
            display.setShowChannelLogos(true)
            display.setPreferTvgName(false)
            display.setHideChannelPrefix(true)
            playback.setAutomaticPictureInPicture(true)
            playback.setPlayerVolume(1f)
            downloads.setUnmeteredNetworkOnly(false)
            downloads.setDestinationRelativePath(DownloadDestinationPolicy.DEFAULT_DESTINATION)
            downloads.setNotificationsEnabled(true)
        }
    }

    private companion object {
        const val PROCESS_SOURCE_ID = "qa-process-death-source"
    }
}
