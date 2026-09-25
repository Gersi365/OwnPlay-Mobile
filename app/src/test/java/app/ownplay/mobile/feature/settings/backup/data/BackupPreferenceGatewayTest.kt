package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.prefs.RestoredActiveSourceIntentStore
import app.ownplay.mobile.downloads.domain.DownloadPreferences
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.settings.data.SourceRefreshAutomaticState
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduleStore
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduler
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceGatewayTest {
    @Test
    fun snapshotAndApplyCoverGlobalActiveAndPerSourceSettings() = runBlocking {
        val fixture = Fixture()
        fixture.active.value = "source-1"
        fixture.refresh.values["source-1"] = SourceRefreshSchedule.EVERY_6_HOURS
        fixture.refresh.wifiOnly["source-1"] = true
        fixture.active.intendedValue = "source-pending"
        val gateway = fixture.gateway()

        val snapshot = gateway.snapshot(listOf("source-1"))
        assertEquals("source-1", snapshot.activeSourceId)
        assertEquals("source-pending", snapshot.intendedActiveSourceId)
        assertEquals(SourceRefreshSchedule.EVERY_6_HOURS, snapshot.refreshSchedules["source-1"])
        assertTrue(snapshot.refreshWifiOnly["source-1"] == true)
        gateway.apply(
            globalSettings = app.ownplay.mobile.feature.settings.backup.domain.BackupGlobalSettings(
                display = DisplayPreferences(
                    compactMediaRows = true,
                    showChannelLogos = false,
                    preferTvgName = true,
                    hideChannelPrefix = true,
                    showCategoryFlags = false,
                    hideLiveCategoryPrefix = true,
                    hideLibraryCategoryPrefix = true,
                ),
                playback = PlaybackPreferences(automaticPictureInPicture = false),
                downloads = DownloadPreferences(unmeteredNetworkOnly = true),
            ),
            activeSourceId = null,
            intendedActiveSourceId = null,
            refreshSchedules = mapOf("source-1" to SourceRefreshSchedule.DAILY),
            refreshWifiOnly = mapOf("source-1" to false),
        )

        assertTrue(fixture.display.state.value.compactMediaRows)
        assertFalse(fixture.display.state.value.showChannelLogos)
        assertTrue(fixture.display.state.value.preferTvgName)
        assertTrue(fixture.display.state.value.hideChannelPrefix)
        assertFalse(fixture.display.state.value.showCategoryFlags)
        assertTrue(fixture.display.state.value.hideLiveCategoryPrefix)
        assertTrue(fixture.display.state.value.hideLibraryCategoryPrefix)
        assertFalse(fixture.playback.state.value.automaticPictureInPicture)
        assertTrue(fixture.download.state.value.unmeteredNetworkOnly)
        assertEquals(null, fixture.active.value)
        assertEquals(null, fixture.active.intendedValue)
        assertEquals(SourceRefreshSchedule.DAILY, fixture.refresh.values["source-1"])
        assertFalse(fixture.refresh.wifiOnly["source-1"] ?: false)
    }

    @Test
    fun restoreReinstatesSnapshotAfterAppliedChanges() = runBlocking {
        val fixture = Fixture()
        fixture.active.value = "source-1"
        fixture.refresh.values["source-1"] = SourceRefreshSchedule.EVERY_6_HOURS
        fixture.refresh.wifiOnly["source-1"] = true
        fixture.active.intendedValue = "source-pending"
        val gateway = fixture.gateway()
        val before = gateway.snapshot(listOf("source-1"))

        gateway.apply(
            globalSettings = app.ownplay.mobile.feature.settings.backup.domain.BackupGlobalSettings(
                display = DisplayPreferences(
                    compactMediaRows = true,
                    showChannelLogos = false,
                    preferTvgName = true,
                    hideChannelPrefix = true,
                    showCategoryFlags = false,
                    hideLiveCategoryPrefix = true,
                    hideLibraryCategoryPrefix = true,
                ),
                playback = PlaybackPreferences(automaticPictureInPicture = false),
                downloads = DownloadPreferences(unmeteredNetworkOnly = true),
            ),
            activeSourceId = null,
            intendedActiveSourceId = null,
            refreshSchedules = mapOf("source-1" to SourceRefreshSchedule.DAILY),
            refreshWifiOnly = mapOf("source-1" to false),
        )
        gateway.restore(before)

        assertFalse(fixture.display.state.value.compactMediaRows)
        assertTrue(fixture.display.state.value.showChannelLogos)
        assertFalse(fixture.display.state.value.preferTvgName)
        assertTrue(fixture.display.state.value.hideChannelPrefix)
        assertTrue(fixture.display.state.value.showCategoryFlags)
        assertFalse(fixture.display.state.value.hideLiveCategoryPrefix)
        assertFalse(fixture.display.state.value.hideLibraryCategoryPrefix)
        assertTrue(fixture.playback.state.value.automaticPictureInPicture)
        assertFalse(fixture.download.state.value.unmeteredNetworkOnly)
        assertEquals("source-1", fixture.active.value)
        assertEquals("source-pending", fixture.active.intendedValue)
        assertEquals(SourceRefreshSchedule.EVERY_6_HOURS, fixture.refresh.values["source-1"])
        assertTrue(fixture.refresh.wifiOnly["source-1"] == true)
    }

    @Test
    fun restoreClearsRefreshScheduleWhenSnapshotHadNoStoredKey() = runBlocking {
        val fixture = Fixture()
        val gateway = fixture.gateway()
        val before = gateway.snapshot(listOf("source-new"))
        assertEquals(null, before.refreshSchedules["source-new"])

        gateway.apply(
            globalSettings = before.globalSettings,
            activeSourceId = before.activeSourceId,
            intendedActiveSourceId = before.intendedActiveSourceId,
            refreshSchedules = mapOf("source-new" to SourceRefreshSchedule.DAILY),
            refreshWifiOnly = mapOf("source-new" to true),
        )
        assertEquals(SourceRefreshSchedule.DAILY, fixture.refresh.values["source-new"])

        gateway.restore(before)
        assertFalse(fixture.refresh.values.containsKey("source-new"))
    }

    @Test
    fun schedulerSyncOnlyTargetsEnabledSourcesAndCountsFailures() {
        val fixture = Fixture()
        fixture.scheduler.failOn += "source-2"
        val failures = fixture.gateway().syncRefreshSchedules(
            schedules = mapOf(
                "source-1" to SourceRefreshSchedule.DAILY,
                "source-2" to SourceRefreshSchedule.EVERY_12_HOURS,
                "source-3" to SourceRefreshSchedule.EVERY_6_HOURS,
            ),
            refreshWifiOnly = mapOf(
                "source-1" to true,
                "source-2" to false,
                "source-3" to true,
            ),
            enabledSourceIds = setOf("source-1", "source-2"),
        )

        assertEquals(1, failures)
        assertEquals(listOf("source-1", "source-2"), fixture.scheduler.applied.map { it.first })
        assertEquals(listOf(true, false), fixture.scheduler.applied.map { it.second })
    }
}

private class Fixture {
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
    val state = MutableStateFlow<String?>(null)
    private val intendedState = MutableStateFlow<String?>(null)
    var value: String?
        get() = state.value
        set(value) { state.value = value }
    var intendedValue: String?
        get() = intendedState.value
        set(value) { intendedState.value = value }
    override val selectedSourceId: Flow<String?> = state
    override val intendedSourceId: Flow<String?> = intendedState
    override suspend fun currentSelectedSourceId(): String? = state.value
    override suspend fun currentIntendedSourceId(): String? = intendedState.value
    override suspend fun setSelectedSourceId(sourceId: String?) { state.value = sourceId }
    override suspend fun setIntendedSourceId(sourceId: String?) { intendedState.value = sourceId }
}

private class FakeDisplayRepository : DisplayPreferencesRepository {
    val state = MutableStateFlow(DisplayPreferences())
    override val preferences: Flow<DisplayPreferences> = state
    override suspend fun setCompactMediaRows(enabled: Boolean): Boolean = update { copy(compactMediaRows = enabled) }
    override suspend fun setShowChannelLogos(enabled: Boolean): Boolean = update { copy(showChannelLogos = enabled) }
    override suspend fun setPreferTvgName(enabled: Boolean): Boolean = update { copy(preferTvgName = enabled) }
    override suspend fun setHideChannelPrefix(enabled: Boolean): Boolean = update { copy(hideChannelPrefix = enabled) }
    override suspend fun setShowCategoryFlags(enabled: Boolean): Boolean = update { copy(showCategoryFlags = enabled) }
    override suspend fun setHideLiveCategoryPrefix(enabled: Boolean): Boolean = update { copy(hideLiveCategoryPrefix = enabled) }
    override suspend fun setHideLibraryCategoryPrefix(enabled: Boolean): Boolean = update { copy(hideLibraryCategoryPrefix = enabled) }
    private fun update(block: DisplayPreferences.() -> DisplayPreferences): Boolean {
        state.value = state.value.block()
        return true
    }
}

private class FakePlaybackRepository : PlaybackPreferencesRepository {
    val state = MutableStateFlow(PlaybackPreferences())
    override val preferences: Flow<PlaybackPreferences> = state

    override suspend fun setAutomaticPictureInPicture(enabled: Boolean): Boolean {
        state.value = state.value.copy(automaticPictureInPicture = enabled)
        return true
    }

    override suspend fun setPlayerVolume(volume: Float): Boolean {
        state.value = state.value.copy(playerVolume = volume.coerceIn(0f, 1f))
        return true
    }
}

private class FakeDownloadRepository : DownloadPreferencesRepository {
    val state = MutableStateFlow(DownloadPreferences())
    override val preferences: Flow<DownloadPreferences> = state
    override suspend fun current(): DownloadPreferences = state.value

    override suspend fun setUnmeteredNetworkOnly(enabled: Boolean): Boolean {
        state.value = state.value.copy(unmeteredNetworkOnly = enabled)
        return true
    }

    override suspend fun setDestinationRelativePath(relativePath: String): Boolean {
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
    val values = mutableMapOf<String, SourceRefreshSchedule>()
    val wifiOnly = mutableMapOf<String, Boolean>()
    private val automatic = mutableMapOf<String, SourceRefreshAutomaticState>()

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
    val applied = mutableListOf<Pair<String, Boolean>>()
    val failOn = mutableSetOf<String>()
    override fun apply(
        sourceId: SourceId,
        schedule: SourceRefreshSchedule,
        wifiOnly: Boolean,
    ) {
        applied += sourceId.value to wifiOnly
        if (sourceId.value in failOn) error("scheduler failure")
    }
    override fun cancel(sourceId: SourceId) = Unit
}
