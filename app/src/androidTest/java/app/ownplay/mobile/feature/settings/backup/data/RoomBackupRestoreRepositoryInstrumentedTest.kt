package app.ownplay.mobile.feature.settings.backup.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.db.SourceEntity
import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.prefs.RestoredActiveSourceIntentStore
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.live.domain.LiveOrganizationMode
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.settings.data.SourceRefreshAutomaticState
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduleStore
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduler
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.feature.settings.backup.domain.BackupCatalogKind
import app.ownplay.mobile.feature.settings.backup.domain.BackupCategoryPersonalization
import app.ownplay.mobile.feature.settings.backup.domain.BackupExportResult
import app.ownplay.mobile.feature.settings.backup.domain.BackupFormatContract
import app.ownplay.mobile.feature.settings.backup.domain.BackupGlobalSettings
import app.ownplay.mobile.feature.settings.backup.domain.BackupLiveCategoryPersonalization
import app.ownplay.mobile.feature.settings.backup.domain.BackupLivePlacementOverride
import app.ownplay.mobile.feature.settings.backup.domain.BackupMediaFavorite
import app.ownplay.mobile.feature.settings.backup.domain.BackupMediaKind
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestorePreview
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestoreResult
import app.ownplay.mobile.feature.settings.backup.domain.BackupSourceDefinition
import app.ownplay.mobile.feature.settings.backup.domain.BackupSourceSettings
import app.ownplay.mobile.feature.settings.backup.domain.BackupValidationCode
import app.ownplay.mobile.feature.settings.backup.domain.OwnPlayBackupEnvelope
import app.ownplay.mobile.feature.settings.backup.domain.OwnPlayBackupPayload
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.sources.domain.SourceType
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupRestoreRepositoryInstrumentedTest {
    private lateinit var database: OwnPlayDatabase
    private lateinit var preferences: PreferenceFixture
    private lateinit var codec: BackupJsonCodec
    private lateinit var repository: RoomBackupRestoreRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OwnPlayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = PreferenceFixture()
        codec = BackupJsonCodec()
        repository = RoomBackupRestoreRepository(
            database = database,
            sourceDao = database.sourceDao(),
            backupDao = database.backupDao(),
            preferences = preferences.gateway(),
            codec = codec,
            now = { Instant.parse("2026-09-18T08:00:00Z") },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun restoreCreatesStableDisabledSourceWithoutCredentialReference() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-local",
                displayName = "Local usable source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://local.example.com",
                credentialReference = "source-local",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        preferences.active.value = "source-local"
        val backup = backupFile()
        assertTrue(repository.previewRestore(backup) is BackupRestorePreview.Ready)

        val result = repository.restore(backup)
        assertTrue(result is BackupRestoreResult.Success)
        val report = (result as BackupRestoreResult.Success).report

        val restored = database.sourceDao().get("source-restored")!!
        assertEquals("Restored source", restored.displayName)
        assertEquals("https://example.com/portal", restored.baseLocator)
        assertFalse(restored.enabled)
        assertNull(restored.credentialReference)
        assertEquals(
            SourceRefreshSchedule.DAILY,
            preferences.refresh.currentOrNull(SourceId("source-restored")),
        )
        assertEquals("source-local", preferences.active.value)
        assertEquals("source-restored", preferences.active.intendedValue)
        assertTrue(preferences.refresh.currentWifiOnly(SourceId("source-restored")))
        assertTrue(preferences.scheduler.applied.isEmpty())
        assertTrue(database.backupDao().getCategoryPersonalization().isEmpty())
        assertTrue(database.backupDao().getMediaFavorites().isEmpty())
        assertEquals(1, report.skippedCategoryPersonalization)
        assertEquals(1, report.skippedMediaFavorites)

        val exported = repository.exportBackup()
        assertTrue(exported is BackupExportResult.Success)
        val exportedBytes = (exported as BackupExportResult.Success).bytes
        assertTrue(exportedBytes.toString(Charsets.UTF_8).trimStart().startsWith("{"))
        val decoded = codec.decode(exportedBytes)
        assertTrue(decoded is BackupJsonDecodeResult.Success)
        val exportedPayload = (decoded as BackupJsonDecodeResult.Success).envelope.payload
        assertEquals("source-restored", exportedPayload.activeSourceId)
        assertTrue(
            exportedPayload.sourceSettings
                .first { it.sourceId == "source-restored" }
                .refreshWifiOnly,
        )
    }

    @Test
    fun mergeKeepsNewerLocalSourceNameAndActivatesUsableIntendedSource() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-restored",
                displayName = "Local source name",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.com/portal",
                credentialReference = "source-restored",
                enabled = true,
                createdAt = 1L,
                updatedAt = 10L,
            ),
        )
        preferences.active.value = "source-restored"

        val result = repository.restore(backupFile())

        assertTrue(result is BackupRestoreResult.Success)
        assertEquals("Local source name", database.sourceDao().get("source-restored")!!.displayName)
        assertEquals("source-restored", preferences.active.value)
        assertNull(preferences.active.intendedValue)
        assertTrue(preferences.refresh.currentWifiOnly(SourceId("source-restored")))
    }

    @Test
    fun restoreAppliesOnlyLibraryPersonalizationWithReconciledCatalogIdentity() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-restored",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.com/portal",
                credentialReference = "source-restored",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val db = database.openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO provider_categories
                (sourceId, kind, categoryKey, providerKey, name, providerOrder, available, lastSeenGeneration)
            VALUES ('source-restored', 'MOVIE', 'movie-cat', 'provider-movie-cat', 'Movies', 0, 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO movies
                (movieId, sourceId, providerStreamId, categoryKey, name, posterUrl, backdropUrl,
                 extension, rating, providerOrder, available, lastSeenGeneration)
            VALUES ('movie-1', 'source-restored', '101', 'movie-cat', 'Movie One', NULL, NULL,
                    'mp4', NULL, 0, 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO series
                (seriesId, sourceId, providerSeriesId, categoryKey, name, posterUrl, backdropUrl,
                 description, rating, providerOrder, available, lastSeenGeneration)
            VALUES ('series-1', 'source-restored', '201', NULL, 'Series One', NULL, NULL,
                    NULL, NULL, 0, 1, 1)
            """.trimIndent(),
        )

        val backup = codec.encode(
            OwnPlayBackupEnvelope(
                createdAt = "2026-09-18T07:30:00Z",
                payload = OwnPlayBackupPayload(
                    sources = listOf(
                        BackupSourceDefinition(
                            sourceId = "source-restored",
                            type = SourceType.XTREAM,
                            displayName = "Backup source",
                            baseLocator = "https://example.com/portal",
                            enabled = true,
                        ),
                    ),
                    categoryPersonalization = listOf(
                        BackupCategoryPersonalization(
                            sourceId = "source-restored",
                            kind = BackupCatalogKind.MOVIE,
                            categoryKey = "movie-cat",
                            hidden = true,
                            manualOrder = 1,
                        ),
                        BackupCategoryPersonalization(
                            sourceId = "source-restored",
                            kind = BackupCatalogKind.MOVIE,
                            categoryKey = "missing-cat",
                            hidden = true,
                            manualOrder = 2,
                        ),
                    ),
                    mediaFavorites = listOf(
                        BackupMediaFavorite("source-restored", BackupMediaKind.MOVIE, "movie-1", 10L),
                        BackupMediaFavorite("source-restored", BackupMediaKind.MOVIE, "missing-movie", 11L),
                        BackupMediaFavorite("source-restored", BackupMediaKind.SERIES, "series-1", 12L),
                        BackupMediaFavorite("source-restored", BackupMediaKind.SERIES, "missing-series", 13L),
                    ),
                ),
            ),
        )

        val result = repository.restore(backup)

        assertTrue(result is BackupRestoreResult.Success)
        val report = (result as BackupRestoreResult.Success).report
        assertEquals(1, report.skippedCategoryPersonalization)
        assertEquals(2, report.skippedMediaFavorites)
        assertEquals(
            listOf("movie-cat"),
            database.backupDao().getCategoryPersonalization().map { it.categoryKey },
        )
        assertEquals(
            setOf("movie-1", "series-1"),
            database.backupDao().getMediaFavorites().map { it.contentId }.toSet(),
        )
    }

    @Test
    fun restoreWithNoActiveSourceRestoresExplicitNullSelection() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-local",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://local.example.com",
                credentialReference = "source-local",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        preferences.active.value = "source-local"
        val backup = codec.encode(
            OwnPlayBackupEnvelope(
                createdAt = "2026-09-18T07:30:00Z",
                payload = OwnPlayBackupPayload(
                    sources = listOf(
                        BackupSourceDefinition(
                            sourceId = "source-local",
                            type = SourceType.XTREAM,
                            displayName = "Backup source name",
                            baseLocator = "https://local.example.com",
                            enabled = true,
                        ),
                    ),
                    activeSourceId = null,
                ),
            ),
        )

        val result = repository.restore(backup)

        assertTrue(result is BackupRestoreResult.Success)
        assertNull(preferences.active.value)
        assertNull(preferences.active.intendedValue)
        assertTrue(database.sourceDao().get("source-local")!!.enabled)
    }

    @Test
    fun restoreNullActivePreferenceProducesExplicitNoActiveSourceState() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-local",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://local.example.com",
                credentialReference = "source-local",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        preferences.active.value = "source-local"
        val backup = codec.encode(
            OwnPlayBackupEnvelope(
                createdAt = "2026-09-18T07:30:00Z",
                payload = OwnPlayBackupPayload(
                    sources = listOf(
                        BackupSourceDefinition(
                            sourceId = "source-restored",
                            type = SourceType.XTREAM,
                            displayName = "Restored source",
                            baseLocator = "https://example.com/portal",
                            enabled = false,
                        ),
                    ),
                    activeSourceId = null,
                ),
            ),
        )

        val result = repository.restore(backup)

        assertTrue(result is BackupRestoreResult.Success)
        assertNull(preferences.active.value)
        assertNull(preferences.active.intendedValue)
    }

    @Test
    fun restoreLiveCategoryPersonalizationRequiresReconciledCategoryIdentity() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-restored",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.com/portal",
                credentialReference = "source-restored",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val db = database.openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO provider_categories
                (sourceId, kind, categoryKey, providerKey, name, providerOrder, available, lastSeenGeneration)
            VALUES ('source-restored', 'LIVE', 'provider-news', '7', 'News', 0, 0, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO ownplay_live_categories
                (sourceId, categoryId, parentCategoryId, displayName, semanticKey, origin, available, lastSeenGeneration)
            VALUES ('source-restored', 'ownplay-known', NULL, 'Known', 'NEWS', 'AUTO', 0, 1)
            """.trimIndent(),
        )

        val backup = codec.encode(
            OwnPlayBackupEnvelope(
                createdAt = "2026-09-18T07:30:00Z",
                payload = OwnPlayBackupPayload(
                    sources = listOf(
                        BackupSourceDefinition(
                            sourceId = "source-restored",
                            type = SourceType.XTREAM,
                            displayName = "Backup source",
                            baseLocator = "https://example.com/portal",
                            enabled = true,
                        ),
                    ),
                    liveCategoryPersonalization = listOf(
                        BackupLiveCategoryPersonalization(
                            "source-restored",
                            LiveOrganizationMode.PROVIDER,
                            "provider-news",
                            true,
                            1,
                        ),
                        BackupLiveCategoryPersonalization(
                            "source-restored",
                            LiveOrganizationMode.PROVIDER,
                            "__provider_uncategorized__",
                            true,
                            2,
                        ),
                        BackupLiveCategoryPersonalization(
                            "source-restored",
                            LiveOrganizationMode.PROVIDER,
                            "provider-missing",
                            true,
                            3,
                        ),
                        BackupLiveCategoryPersonalization(
                            "source-restored",
                            LiveOrganizationMode.OWNPLAY,
                            "ownplay-known",
                            true,
                            4,
                        ),
                        BackupLiveCategoryPersonalization(
                            "source-restored",
                            LiveOrganizationMode.OWNPLAY,
                            "ownplay-missing",
                            true,
                            5,
                        ),
                    ),
                ),
            ),
        )

        val result = repository.restore(backup)

        assertTrue(result is BackupRestoreResult.Success)
        val report = (result as BackupRestoreResult.Success).report
        assertEquals(3, report.skippedCategoryPersonalization)
        assertEquals(
            setOf("provider-news", "__provider_uncategorized__"),
            database.backupDao().getLiveCategoryPersonalization().map { it.categoryId }.toSet(),
        )
    }

    @Test
    fun legacyOwnPlayBackupRestoresProviderOnlyStateAndSkipsPlacementOverrides() = runBlocking {
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-restored",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://example.com/portal",
                credentialReference = "source-restored",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.refreshStateDao().upsertLiveChannels(
            listOf(
                app.ownplay.mobile.data.db.LiveChannelEntity(
                    channelId = "channel-1",
                    sourceId = "source-restored",
                    providerKey = "channel-1",
                    providerStreamId = "1",
                    categoryKey = null,
                    name = "Channel One",
                    tvgId = null,
                    tvgName = null,
                    logoUrl = null,
                    streamLocator = "opaque://channel-1",
                    providerOrder = 0,
                    available = true,
                    lastSeenGeneration = 1L,
                ),
            ),
        )

        val backup = codec.encode(
            OwnPlayBackupEnvelope(
                createdAt = "2026-09-18T07:30:00Z",
                payload = OwnPlayBackupPayload(
                    sources = listOf(
                        BackupSourceDefinition(
                            sourceId = "source-restored",
                            type = SourceType.XTREAM,
                            displayName = "Backup source",
                            baseLocator = "https://example.com/portal",
                            enabled = true,
                        ),
                    ),
                    sourceSettings = listOf(
                        BackupSourceSettings(
                            sourceId = "source-restored",
                            liveOrganizationMode = LiveOrganizationMode.OWNPLAY,
                        ),
                    ),
                    livePlacementOverrides = listOf(
                        BackupLivePlacementOverride(
                            sourceId = "source-restored",
                            categoryId = "country:AL::NEWS",
                            channelId = "channel-1",
                            hidden = false,
                            manualOrder = 0,
                        ),
                    ),
                ),
            ),
        )

        val result = repository.restore(backup)

        assertTrue(result is BackupRestoreResult.Success)
        val report = (result as BackupRestoreResult.Success).report
        assertEquals(1, report.skippedLivePlacements)
        assertTrue(database.backupDao().getManualPlacementOverrides().isEmpty())
        assertEquals(
            LiveOrganizationMode.PROVIDER.name,
            database.backupDao().getLivePreferences().single().activeMode,
        )
    }

    @Test
    fun preferenceFailureRollsBackRoomAndCompensatesGlobalPreferences() = runBlocking {
        preferences.playback.failNext = true

        val result = repository.restore(backupFile())

        assertTrue(result is BackupRestoreResult.StorageFailure)
        assertTrue(database.sourceDao().getAll().isEmpty())
        assertFalse(preferences.display.state.value.compactMediaRows)
        assertTrue(preferences.display.state.value.showChannelLogos)
        assertFalse(preferences.display.state.value.preferTvgName)
        assertTrue(preferences.playback.state.value.automaticPictureInPicture)
        assertFalse(preferences.download.state.value.unmeteredNetworkOnly)
        assertNull(preferences.active.value)
        assertNull(preferences.active.intendedValue)
    }

    @Test
    fun latePreferenceFailureRollsBackRoomAndCompensatesEarlierPreferenceWrites() = runBlocking {
        preferences.download.failNextDestination = true

        val result = repository.restore(backupFile())

        assertTrue(result is BackupRestoreResult.StorageFailure)
        assertTrue(database.sourceDao().getAll().isEmpty())
        assertFalse(preferences.display.state.value.compactMediaRows)
        assertTrue(preferences.display.state.value.showChannelLogos)
        assertFalse(preferences.display.state.value.preferTvgName)
        assertTrue(preferences.display.state.value.hideChannelPrefix)
        assertTrue(preferences.playback.state.value.automaticPictureInPicture)
        assertEquals(1f, preferences.playback.state.value.playerVolume)
        assertFalse(preferences.download.state.value.unmeteredNetworkOnly)
        assertNull(preferences.active.value)
        assertNull(preferences.active.intendedValue)
    }

    @Test
    fun refreshScheduleFailureRollsBackRoomAndRestoresActiveAndGlobalPreferences() = runBlocking {
        preferences.refresh.failNextSet = true

        val result = repository.restore(backupFile())

        assertTrue(result is BackupRestoreResult.StorageFailure)
        assertTrue(database.sourceDao().getAll().isEmpty())
        assertFalse(preferences.display.state.value.compactMediaRows)
        assertTrue(preferences.display.state.value.showChannelLogos)
        assertFalse(preferences.display.state.value.preferTvgName)
        assertTrue(preferences.display.state.value.hideChannelPrefix)
        assertTrue(preferences.playback.state.value.automaticPictureInPicture)
        assertEquals(1f, preferences.playback.state.value.playerVolume)
        assertFalse(preferences.download.state.value.unmeteredNetworkOnly)
        assertNull(preferences.active.value)
        assertNull(preferences.active.intendedValue)
        assertNull(preferences.refresh.currentOrNull(SourceId("source-restored")))
        assertFalse(preferences.refresh.currentWifiOnly(SourceId("source-restored")))
    }

    @Test
    fun malformedBackupIsRejectedWithoutMutatingDurableState() = runBlocking {
        val existing = SourceEntity(
            sourceId = "source-local",
            displayName = "Local source",
            type = SourceType.XTREAM.name,
            baseLocator = "https://local.example.com",
            credentialReference = "source-local",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        database.sourceDao().insert(existing)
        preferences.active.value = existing.sourceId
        preferences.display.state.value = DisplayPreferences(compactMediaRows = true)

        val result = repository.restore("not-an-ownplay-backup".toByteArray())

        assertTrue(result is BackupRestoreResult.Rejected)
        val issues = (result as BackupRestoreResult.Rejected).issues
        assertTrue(issues.any { it.code == BackupValidationCode.INVALID_BACKUP_FILE })
        assertEquals(listOf(existing), database.sourceDao().getAll())
        assertEquals(existing.sourceId, preferences.active.value)
        assertTrue(preferences.display.state.value.compactMediaRows)
    }

    @Test
    fun newerBackupVersionIsRejectedBeforeAnyMutation() = runBlocking {
        val existing = SourceEntity(
            sourceId = "source-local",
            displayName = "Local source",
            type = SourceType.XTREAM.name,
            baseLocator = "https://local.example.com",
            credentialReference = "source-local",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )
        database.sourceDao().insert(existing)
        preferences.active.value = existing.sourceId

        val result = repository.restore(newerVersionBackupFile())

        assertTrue(result is BackupRestoreResult.Rejected)
        val issues = (result as BackupRestoreResult.Rejected).issues
        assertTrue(issues.any { it.code == BackupValidationCode.UNSUPPORTED_NEWER_VERSION })
        assertEquals(listOf(existing), database.sourceDao().getAll())
        assertEquals(existing.sourceId, preferences.active.value)
    }

    @Test
    fun exportedJsonDoesNotContainRoomCredentialReferenceInPlaintext() = runBlocking {
        val marker = "credential-reference-must-not-export"
        database.sourceDao().insert(
            SourceEntity(
                sourceId = "source-local",
                displayName = "Local source",
                type = SourceType.XTREAM.name,
                baseLocator = "https://local.example.com",
                credentialReference = marker,
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val result = repository.exportBackup()

        assertTrue(result is BackupExportResult.Success)
        val bytes = (result as BackupExportResult.Success).bytes
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(marker))
        val decoded = codec.decode(bytes)
        assertTrue(decoded is BackupJsonDecodeResult.Success)
        val source = (decoded as BackupJsonDecodeResult.Success).envelope.payload.sources.single()
        assertEquals("source-local", source.sourceId)
        assertEquals("https://local.example.com", source.baseLocator)
    }

    private fun newerVersionBackupFile(): ByteArray =
        """
        {
          "format": "${BackupFormatContract.FORMAT}",
          "version": ${BackupFormatContract.VERSION + 1},
          "createdAt": "2026-09-18T07:30:00Z",
          "payload": {}
        }
        """.trimIndent().toByteArray()

    private fun backupFile(): ByteArray = codec.encode(
        OwnPlayBackupEnvelope(
            createdAt = "2026-09-18T07:30:00Z",
            payload = OwnPlayBackupPayload(
                sources = listOf(
                    BackupSourceDefinition(
                        sourceId = "source-restored",
                        type = SourceType.XTREAM,
                        displayName = "Restored source",
                        baseLocator = "https://example.com/portal",
                        enabled = true,
                    ),
                ),
                activeSourceId = "source-restored",
                globalSettings = BackupGlobalSettings(
                    display = DisplayPreferences(compactMediaRows = true, showChannelLogos = false, preferTvgName = true, hideChannelPrefix = true),
                    playback = PlaybackPreferences(automaticPictureInPicture = false),
                    downloads = DownloadPreferences(unmeteredNetworkOnly = true),
                ),
                sourceSettings = listOf(
                    BackupSourceSettings(
                        sourceId = "source-restored",
                        refreshSchedule = SourceRefreshSchedule.DAILY,
                        refreshWifiOnly = true,
                        liveOrganizationMode = LiveOrganizationMode.OWNPLAY,
                    ),
                ),
                categoryPersonalization = listOf(
                    BackupCategoryPersonalization(
                        sourceId = "source-restored",
                        kind = BackupCatalogKind.LIVE,
                        categoryKey = "news",
                        hidden = true,
                        manualOrder = 2,
                    ),
                ),
                mediaFavorites = listOf(
                    BackupMediaFavorite(
                        sourceId = "source-restored",
                        mediaKind = BackupMediaKind.MOVIE,
                        contentId = "movie-missing",
                        addedAt = 1L,
                    ),
                ),
            ),
        ),
    )
}

private class PreferenceFixture {
    val active = FakeActiveSourceStore()
    val display = FakeDisplayRepository()
    val playback = FakePlaybackRepository()
    val download = FakeDownloadRepository()
    val refresh = FakeRefreshStore()
    val scheduler = FakeRefreshScheduler()

    fun gateway() = BackupPreferenceGateway(
        activeSourceStore = active,
        intendedActiveSourceStore = active,
        displayRepository = display,
        playbackRepository = playback,
        downloadRepository = download,
        refreshStore = refresh,
        refreshScheduler = scheduler,
    )
}

private class FakeActiveSourceStore : ActiveSourceSelectionStore, RestoredActiveSourceIntentStore {
    private val selected = MutableStateFlow<String?>(null)
    private val intended = MutableStateFlow<String?>(null)
    var value: String?
        get() = selected.value
        set(value) { selected.value = value }
    var intendedValue: String?
        get() = intended.value
        set(value) { intended.value = value }

    override val selectedSourceId: Flow<String?> = selected
    override val intendedSourceId: Flow<String?> = intended
    override suspend fun currentSelectedSourceId(): String? = selected.value
    override suspend fun currentIntendedSourceId(): String? = intended.value
    override suspend fun setSelectedSourceId(sourceId: String?) {
        selected.value = sourceId
    }
    override suspend fun setIntendedSourceId(sourceId: String?) {
        intended.value = sourceId
    }
}

private class FakeDisplayRepository : DisplayPreferencesRepository {
    val state = MutableStateFlow(DisplayPreferences())
    override val preferences: Flow<DisplayPreferences> = state

    override suspend fun setCompactMediaRows(enabled: Boolean): Boolean = update { copy(compactMediaRows = enabled) }
    override suspend fun setShowChannelLogos(enabled: Boolean): Boolean = update { copy(showChannelLogos = enabled) }
    override suspend fun setPreferTvgName(enabled: Boolean): Boolean = update { copy(preferTvgName = enabled) }
    override suspend fun setHideChannelPrefix(enabled: Boolean): Boolean = update { copy(hideChannelPrefix = enabled) }
    override suspend fun setHideCategoryPrefix(enabled: Boolean): Boolean = update { copy(hideCategoryPrefix = enabled) }

    private fun update(block: DisplayPreferences.() -> DisplayPreferences): Boolean {
        state.value = state.value.block()
        return true
    }
}
private class FakePlaybackRepository : PlaybackPreferencesRepository {
    val state = MutableStateFlow(PlaybackPreferences())
    var failNext: Boolean = false
    override val preferences: Flow<PlaybackPreferences> = state

    override suspend fun setAutomaticPictureInPicture(enabled: Boolean): Boolean =
        update { copy(automaticPictureInPicture = enabled) }

    override suspend fun setPlayerVolume(volume: Float): Boolean =
        update { copy(playerVolume = volume.coerceIn(0f, 1f)) }

    private fun update(block: PlaybackPreferences.() -> PlaybackPreferences): Boolean {
        if (failNext) {
            failNext = false
            return false
        }
        state.value = state.value.block()
        return true
    }
}

private class FakeDownloadRepository : DownloadPreferencesRepository {
    val state = MutableStateFlow(DownloadPreferences())
    var failNextDestination: Boolean = false
    override val preferences: Flow<DownloadPreferences> = state
    override suspend fun current(): DownloadPreferences = state.value

    override suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean {
        state.value = state.value.copy(unmeteredNetworkOnly = enabled)
        return true
    }

    override suspend fun setDestinationRelativePath(relativePath: String): Boolean {
        if (failNextDestination) {
            failNextDestination = false
            return false
        }
        val normalized = app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy.normalize(relativePath)
            ?: return false
        state.value = state.value.copy(destinationRelativePath = normalized)
        return true
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean): Boolean {
        state.value = state.value.copy(notificationsEnabled = enabled)
        return true
    }
}

private class FakeRefreshStore : SourceRefreshScheduleStore {
    private val values = mutableMapOf<String, SourceRefreshSchedule>()
    private val wifiOnly = mutableMapOf<String, Boolean>()
    private val automatic = mutableMapOf<String, SourceRefreshAutomaticState>()
    var failNextSet: Boolean = false

    override fun observe(sourceId: SourceId): Flow<SourceRefreshSchedule> =
        MutableStateFlow(values[sourceId.value] ?: SourceRefreshSchedule.MANUAL)

    override fun observeWifiOnly(sourceId: SourceId): Flow<Boolean> =
        MutableStateFlow(wifiOnly[sourceId.value] ?: false)

    override suspend fun current(sourceId: SourceId): SourceRefreshSchedule =
        values[sourceId.value] ?: SourceRefreshSchedule.MANUAL

    override suspend fun currentOrNull(sourceId: SourceId): SourceRefreshSchedule? =
        values[sourceId.value]

    override suspend fun currentWifiOnly(sourceId: SourceId): Boolean =
        wifiOnly[sourceId.value] ?: false

    override suspend fun currentAutomaticState(sourceId: SourceId): SourceRefreshAutomaticState =
        automatic[sourceId.value] ?: SourceRefreshAutomaticState()

    override suspend fun set(sourceId: SourceId, schedule: SourceRefreshSchedule) {
        if (failNextSet) {
            failNextSet = false
            error("refresh schedule write failed")
        }
        values[sourceId.value] = schedule
    }

    override suspend fun setWifiOnly(sourceId: SourceId, enabled: Boolean) {
        wifiOnly[sourceId.value] = enabled
    }

    override suspend fun recordAutomaticSuccess(sourceId: SourceId) {
        automatic[sourceId.value] = SourceRefreshAutomaticState()
    }

    override suspend fun recordAutomaticFailure(
        sourceId: SourceId,
        category: app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory,
    ): SourceRefreshAutomaticState {
        val prior = automatic[sourceId.value] ?: SourceRefreshAutomaticState()
        val next = SourceRefreshAutomaticState(
            consecutiveFailures = prior.consecutiveFailures + 1,
            authenticationSuspended =
                category == app.ownplay.mobile.sources.domain.SourceRefreshFailureCategory.AUTHENTICATION,
        )
        automatic[sourceId.value] = next
        return next
    }

    override suspend fun clearAutomaticSuspension(sourceId: SourceId) {
        automatic[sourceId.value] = SourceRefreshAutomaticState()
    }

    override suspend fun clear(sourceId: SourceId) {
        values.remove(sourceId.value)
        wifiOnly.remove(sourceId.value)
        automatic.remove(sourceId.value)
    }
}

private class FakeRefreshScheduler : SourceRefreshScheduler {
    val applied = mutableListOf<Pair<SourceId, SourceRefreshSchedule>>()

    override fun apply(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
        wifiOnly: Boolean,
    ) {
        applied += sourceId to schedule
    }

    override fun cancel(sourceId: SourceId) = Unit
}
