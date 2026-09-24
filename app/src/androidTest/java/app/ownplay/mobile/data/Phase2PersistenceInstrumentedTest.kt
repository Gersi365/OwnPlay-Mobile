package app.ownplay.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ownplay.mobile.data.db.ChannelPersonalizationEntity
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveOrganizationPreferenceEntity
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.data.db.RefreshStateEntity
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.prefs.ActiveSourcePreferences
import app.ownplay.mobile.downloads.data.DataStoreDownloadPreferencesRepository
import app.ownplay.mobile.downloads.data.DownloadPreferencesDataStore
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.feature.playback.data.DataStorePlaybackPreferencesRepository
import app.ownplay.mobile.feature.playback.data.PlaybackPreferencesDataStore
import app.ownplay.mobile.feature.settings.data.DataStoreDisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.data.DisplayPreferencesDataStore
import app.ownplay.mobile.feature.settings.data.SourceRefreshSchedulePreferences
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase2PersistenceInstrumentedTest {
    @Test
    fun roomStateSurvivesCloseAndReopenWithoutMergingProviderAndPersonalizationState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)

        var database = openDatabase(context)
        try {
            val source = SourceEntity(
                sourceId = SOURCE_ID,
                displayName = "Phase 2",
                type = "XTREAM",
                baseLocator = "https://provider.invalid",
                credentialReference = SOURCE_ID,
                enabled = true,
                createdAt = 10L,
                updatedAt = 10L,
            )
            val category = ProviderCategoryEntity(
                sourceId = SOURCE_ID,
                kind = "LIVE",
                categoryKey = "news",
                providerKey = "news",
                name = "News",
                providerOrder = 1,
                available = true,
                lastSeenGeneration = 1L,
            )
            val channel = LiveChannelEntity(
                channelId = CHANNEL_ID,
                sourceId = SOURCE_ID,
                providerKey = "provider-channel-1",
                providerStreamId = "1",
                categoryKey = "news",
                name = "Provider Name",
                tvgId = "phase2.tvg",
                tvgName = "Provider TVG Name",
                logoUrl = null,
                streamLocator = "opaque-phase2-stream",
                providerOrder = 1,
                available = true,
                lastSeenGeneration = 1L,
            )

            database.sourceDao().insert(source)
            database.refreshStateDao().upsertCategories(listOf(category))
            database.refreshStateDao().upsertLiveChannels(listOf(channel))
            database.liveOrganizationDao().upsertChannelPersonalization(
                ChannelPersonalizationEntity(
                    channelId = CHANNEL_ID,
                    favorite = true,
                    hidden = false,
                    localName = "My News",
                    localLogo = null,
                    manualOrder = 4,
                ),
            )
            database.liveOrganizationDao().upsertPreference(
                LiveOrganizationPreferenceEntity(
                    sourceId = SOURCE_ID,
                    activeMode = "OWNPLAY",
                ),
            )
            database.refreshStateDao().upsert(
                RefreshStateEntity(
                    sourceId = SOURCE_ID,
                    generation = 1L,
                    state = "SUCCESS",
                    lastAttempt = 20L,
                    lastSuccess = 20L,
                    errorCode = null,
                ),
            )
            database.downloadDao().upsert(
                DownloadEntity(
                    downloadId = DOWNLOAD_ID,
                    sourceId = SOURCE_ID,
                    mediaKind = "MOVIE",
                    contentId = "movie-phase2",
                    title = "Phase 2 Movie",
                    streamIdentity = "opaque-download",
                    state = "COMPLETED",
                    bytesDownloaded = 100L,
                    totalBytes = 100L,
                    localReference = "content://phase2/download",
                    integrityMetadata = "sha256:test",
                    failureReason = null,
                    createdAt = 30L,
                    updatedAt = 31L,
                ),
            )

            database.refreshStateDao().upsertLiveChannels(
                listOf(
                    channel.copy(
                        name = "Provider Name Updated",
                        lastSeenGeneration = 2L,
                    ),
                ),
            )
        } finally {
            database.close()
        }

        database = openDatabase(context)
        try {
            val source = database.sourceDao().get(SOURCE_ID)
            assertNotNull(source)
            assertEquals("Phase 2", source?.displayName)

            val categories = database.refreshStateDao().getCategoriesForRefresh(SOURCE_ID)
            assertEquals(listOf("news"), categories.map { it.categoryKey })

            val channels = database.refreshStateDao().getLiveChannelsForRefresh(SOURCE_ID)
            assertEquals("Provider Name Updated", channels.single().name)
            assertEquals(2L, channels.single().lastSeenGeneration)

            val personalization = database.liveOrganizationDao().getChannelPersonalization(CHANNEL_ID)
            assertNotNull(personalization)
            assertTrue(personalization?.favorite == true)
            assertEquals("My News", personalization?.localName)
            assertEquals(4, personalization?.manualOrder)

            val livePreference = database.liveOrganizationDao().observePreference(SOURCE_ID).first()
            assertEquals("OWNPLAY", livePreference?.activeMode)

            val refresh = database.refreshStateDao().get(SOURCE_ID)
            assertEquals(1L, refresh?.generation)
            assertEquals("SUCCESS", refresh?.state)

            val download = database.downloadDao().get(DOWNLOAD_ID)
            assertEquals("COMPLETED", download?.state)
            assertEquals(100L, download?.bytesDownloaded)
        } finally {
            database.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun dataStorePreferencesSurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = SourceId(PREFERENCE_SOURCE_ID)

        val activeSource = ActiveSourcePreferences(context)
        val display = DataStoreDisplayPreferencesRepository(DisplayPreferencesDataStore(context))
        val playback = DataStorePlaybackPreferencesRepository(PlaybackPreferencesDataStore(context))
        val downloads = DataStoreDownloadPreferencesRepository(DownloadPreferencesDataStore(context))
        val refresh = SourceRefreshSchedulePreferences(context)

        try {
            activeSource.setSelectedSourceId(sourceId.value)
            assertTrue(display.setCompactMediaRows(true))
            assertTrue(display.setShowChannelLogos(false))
            assertTrue(display.setPreferTvgName(true))
            assertTrue(display.setHideChannelPrefix(false))
            assertTrue(playback.setAutomaticPictureInPicture(false))
            assertTrue(playback.setPlayerVolume(0.35f))
            assertTrue(downloads.setUnmeteredNetworkOnly(true))
            assertTrue(downloads.setDestinationRelativePath("Download/OwnPlay Phase 2/"))
            assertTrue(downloads.setNotificationsEnabled(false))
            refresh.set(sourceId, SourceRefreshSchedule.EVERY_48_HOURS)
            refresh.setWifiOnly(sourceId, true)
            refresh.recordAutomaticFailure(sourceId, SourceRefreshFailureCategory.NETWORK)

            val reloadedActiveSource = ActiveSourcePreferences(context)
            val reloadedDisplay =
                DataStoreDisplayPreferencesRepository(DisplayPreferencesDataStore(context))
            val reloadedPlayback =
                DataStorePlaybackPreferencesRepository(PlaybackPreferencesDataStore(context))
            val reloadedDownloads =
                DataStoreDownloadPreferencesRepository(DownloadPreferencesDataStore(context))
            val reloadedRefresh = SourceRefreshSchedulePreferences(context)

            assertEquals(sourceId.value, reloadedActiveSource.currentSelectedSourceId())

            val displayState = reloadedDisplay.preferences.first()
            assertTrue(displayState.compactMediaRows)
            assertFalse(displayState.showChannelLogos)
            assertTrue(displayState.preferTvgName)
            assertFalse(displayState.hideChannelPrefix)

            val playbackState = reloadedPlayback.preferences.first()
            assertFalse(playbackState.automaticPictureInPicture)
            assertEquals(0.35f, playbackState.playerVolume)

            val downloadState = reloadedDownloads.current()
            assertTrue(downloadState.wifiOnly)
            assertEquals("Download/OwnPlay Phase 2/", downloadState.destinationRelativePath)
            assertFalse(downloadState.notificationsEnabled)

            assertEquals(SourceRefreshSchedule.EVERY_48_HOURS, reloadedRefresh.current(sourceId))
            assertTrue(reloadedRefresh.currentWifiOnly(sourceId))
            assertEquals(1, reloadedRefresh.currentAutomaticState(sourceId).consecutiveFailures)
            assertFalse(reloadedRefresh.currentAutomaticState(sourceId).authenticationSuspended)
        } finally {
            activeSource.setSelectedSourceId(null)
            display.setCompactMediaRows(false)
            display.setShowChannelLogos(true)
            display.setPreferTvgName(false)
            display.setHideChannelPrefix(true)
            playback.setAutomaticPictureInPicture(true)
            playback.setPlayerVolume(1f)
            downloads.setUnmeteredNetworkOnly(false)
            downloads.setDestinationRelativePath(DownloadDestinationPolicy.DEFAULT_DESTINATION)
            downloads.setNotificationsEnabled(true)
            refresh.clear(sourceId)
        }
    }

    private fun openDatabase(context: Context): OwnPlayDatabase =
        Room.databaseBuilder(context, OwnPlayDatabase::class.java, DB_NAME)
            .addMigrations(
                OwnPlayDatabase.MIGRATION_1_2,
                OwnPlayDatabase.MIGRATION_2_3,
            )
            .build()

    private companion object {
        const val DB_NAME = "phase2-persistence.db"
        const val SOURCE_ID = "phase2-source"
        const val CHANNEL_ID = "phase2-channel"
        const val DOWNLOAD_ID = "phase2-download"
        const val PREFERENCE_SOURCE_ID = "phase2-preference-source"
    }
}
