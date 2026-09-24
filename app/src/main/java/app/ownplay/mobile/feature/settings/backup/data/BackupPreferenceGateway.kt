package app.ownplay.mobile.feature.settings.backup.data

import app.ownplay.mobile.data.prefs.ActiveSourceSelectionStore
import app.ownplay.mobile.data.prefs.RestoredActiveSourceIntentStore
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduleStore
import app.ownplay.mobile.feature.settings.data.SourceRefreshScheduler
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.SourceRefreshSchedule
import app.ownplay.mobile.feature.settings.backup.domain.BackupGlobalSettings
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.first

data class BackupPreferenceSnapshot(
    val globalSettings: BackupGlobalSettings,
    val activeSourceId: String?,
    val intendedActiveSourceId: String?,
    val refreshSchedules: Map<String, SourceRefreshSchedule?>,
    val refreshWifiOnly: Map<String, Boolean>,
)

internal class BackupPreferenceGateway(
    private val activeSourceStore: ActiveSourceSelectionStore,
    private val intendedActiveSourceStore: RestoredActiveSourceIntentStore,
    private val displayRepository: DisplayPreferencesRepository,
    private val playbackRepository: PlaybackPreferencesRepository,
    private val downloadRepository: DownloadPreferencesRepository,
    private val refreshStore: SourceRefreshScheduleStore,
    private val refreshScheduler: SourceRefreshScheduler,
) {
    suspend fun snapshot(sourceIds: Collection<String>): BackupPreferenceSnapshot =
        BackupPreferenceSnapshot(
            globalSettings = BackupGlobalSettings(
                display = displayRepository.preferences.first(),
                playback = playbackRepository.preferences.first(),
                downloads = downloadRepository.current(),
            ),
            activeSourceId = activeSourceStore.currentSelectedSourceId(),
            intendedActiveSourceId = intendedActiveSourceStore.currentIntendedSourceId(),
            refreshSchedules = sourceIds.associateWith { sourceId ->
                refreshStore.currentOrNull(SourceId(sourceId))
            },
            refreshWifiOnly = sourceIds.associateWith { sourceId ->
                refreshStore.currentWifiOnly(SourceId(sourceId))
            },
        )

    suspend fun apply(
        globalSettings: BackupGlobalSettings,
        activeSourceId: String?,
        intendedActiveSourceId: String?,
        refreshSchedules: Map<String, SourceRefreshSchedule>,
        refreshWifiOnly: Map<String, Boolean>,
    ) {
        check(displayRepository.setCompactMediaRows(globalSettings.display.compactMediaRows))
        check(displayRepository.setShowChannelLogos(globalSettings.display.showChannelLogos))
        check(displayRepository.setPreferTvgName(globalSettings.display.preferTvgName))
        check(displayRepository.setHideChannelPrefix(globalSettings.display.hideChannelPrefix))
        check(displayRepository.setHideCategoryPrefix(globalSettings.display.hideCategoryPrefix))
        check(playbackRepository.setAutomaticPictureInPicture(globalSettings.playback.automaticPictureInPicture))
        check(playbackRepository.setPlayerVolume(globalSettings.playback.playerVolume))
        check(downloadRepository.setUnmeteredNetworkOnly(globalSettings.downloads.unmeteredNetworkOnly))
        check(downloadRepository.setDestinationRelativePath(globalSettings.downloads.destinationRelativePath))
        check(downloadRepository.setNotificationsEnabled(globalSettings.downloads.notificationsEnabled))
        activeSourceStore.setSelectedSourceId(activeSourceId)
        intendedActiveSourceStore.setIntendedSourceId(intendedActiveSourceId)
        refreshSchedules.forEach { (sourceId, schedule) ->
            refreshStore.set(SourceId(sourceId), schedule)
            refreshStore.setWifiOnly(
                SourceId(sourceId),
                refreshWifiOnly[sourceId] ?: false,
            )
        }
    }

    suspend fun restore(snapshot: BackupPreferenceSnapshot) {
        check(displayRepository.setCompactMediaRows(snapshot.globalSettings.display.compactMediaRows))
        check(displayRepository.setShowChannelLogos(snapshot.globalSettings.display.showChannelLogos))
        check(displayRepository.setPreferTvgName(snapshot.globalSettings.display.preferTvgName))
        check(displayRepository.setHideChannelPrefix(snapshot.globalSettings.display.hideChannelPrefix))
        check(displayRepository.setHideCategoryPrefix(snapshot.globalSettings.display.hideCategoryPrefix))
        check(playbackRepository.setAutomaticPictureInPicture(snapshot.globalSettings.playback.automaticPictureInPicture))
        check(playbackRepository.setPlayerVolume(snapshot.globalSettings.playback.playerVolume))
        check(downloadRepository.setUnmeteredNetworkOnly(snapshot.globalSettings.downloads.unmeteredNetworkOnly))
        check(downloadRepository.setDestinationRelativePath(snapshot.globalSettings.downloads.destinationRelativePath))
        check(downloadRepository.setNotificationsEnabled(snapshot.globalSettings.downloads.notificationsEnabled))
        activeSourceStore.setSelectedSourceId(snapshot.activeSourceId)
        intendedActiveSourceStore.setIntendedSourceId(snapshot.intendedActiveSourceId)
        snapshot.refreshSchedules.forEach { (sourceId, schedule) ->
            val source = SourceId(sourceId)
            if (schedule == null) {
                refreshStore.clear(source)
                if (snapshot.refreshWifiOnly[sourceId] == true) {
                    refreshStore.setWifiOnly(source, true)
                }
            } else {
                refreshStore.set(source, schedule)
                refreshStore.setWifiOnly(
                    source,
                    snapshot.refreshWifiOnly[sourceId] ?: false,
                )
            }
        }
    }

    fun syncRefreshSchedules(
        schedules: Map<String, SourceRefreshSchedule>,
        refreshWifiOnly: Map<String, Boolean>,
        enabledSourceIds: Set<String>,
    ): Int {
        var failures = 0
        schedules.forEach { (sourceId, schedule) ->
            if (sourceId !in enabledSourceIds) return@forEach
            if (runCatching { refreshScheduler.apply(
                SourceId(sourceId),
                schedule,
                refreshWifiOnly[sourceId] ?: false,
            ) }.isFailure) {
                failures += 1
            }
        }
        return failures
    }
}
