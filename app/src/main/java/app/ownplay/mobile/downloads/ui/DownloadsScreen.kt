package app.ownplay.mobile.downloads.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import app.ownplay.mobile.design.rememberContextLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayFeaturePlaceholder
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.downloads.data.ExternalDownloadImportIssue
import app.ownplay.mobile.downloads.data.ExternalDownloadImportIssueKind
import app.ownplay.mobile.downloads.data.ExternalDownloadImportMatch
import app.ownplay.mobile.downloads.data.ExternalDownloadImportResult
import app.ownplay.mobile.downloads.data.ExternalDownloadScanStatus
import app.ownplay.mobile.downloads.domain.DownloadActionHandler
import app.ownplay.mobile.downloads.domain.CatchUpDownloadIdentity
import app.ownplay.mobile.downloads.domain.DownloadDetailsNavigation
import app.ownplay.mobile.downloads.domain.DownloadEpisodeContext
import app.ownplay.mobile.downloads.domain.DownloadItem
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadOrigin
import app.ownplay.mobile.downloads.domain.DownloadRemovalPolicy
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.downloads.domain.DownloadUserAction
import app.ownplay.mobile.downloads.domain.DownloadUserActionPolicy
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.sources.domain.SourceId
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.feature.live.domain.LiveRecordingRemovalPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingFailureCodes
import app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class DownloadLibraryFilter(val label: String) {
    ALL("All types"),
    RECORDINGS("Recordings"),
    CATCH_UP("Catch-up"),
    MOVIES("Movies"),
    SERIES("Series"),
}

private fun safeFailureReasonLabel(code: String): String = when (code) {
    LiveRecordingFailureCodes.START_WINDOW_MISSED -> "The recording window was missed"
    LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_MISSING -> "Exact-alarm permission is required"
    LiveRecordingFailureCodes.EXACT_ALARM_PERMISSION_REVOKED -> "Exact-alarm permission was revoked"
    LiveRecordingFailureCodes.EXACT_ALARM_SCHEDULE_ERROR -> "Android could not arm the recording alarm"
    LiveRecordingFailureCodes.ANDROID_BACKGROUND_START_DENIED -> "Android denied the background service start"
    LiveRecordingFailureCodes.ANDROID_SERVICE_TIME_LIMIT -> "Android's recording service time limit was reached"
    LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_PLAYBACK -> "Live playback used the available provider connection"
    LiveRecordingFailureCodes.LIVE_SLOT_USED_BY_RECORDING -> "Another recording used the available Live connection"
    LiveRecordingFailureCodes.APP_RECORDING_SERVICE_BUSY -> "OwnPlay's single-recording service was busy"
    LiveRecordingFailureCodes.PLAYBACK_PREEMPTED_RECORDING -> "Playback took priority over the recording"
    LiveRecordingFailureCodes.USER_STOPPED -> "Stopped by the user"
    LiveRecordingFailureCodes.PROCESS_INTERRUPTED -> "The recording process was interrupted"
    LiveRecordingFailureCodes.AUTHORIZATION -> "The provider did not authorize recording"
    LiveRecordingFailureCodes.UNSUPPORTED_FORMAT -> "The stream format cannot be recorded safely"
    LiveRecordingFailureCodes.STORAGE -> "Recording storage failed"
    LiveRecordingFailureCodes.SOURCE_UNAVAILABLE -> "The Live source was unavailable"
    LiveRecordingFailureCodes.NETWORK -> "The stream or network connection failed"
    LiveRecordingFailureCodes.UNKNOWN -> "The recording failed for an unknown reason"
    else -> "The recording failed; details are unavailable"
}

private fun safeFailureStageLabel(stage: String): String = when (stage) {
    "SCHEDULE" -> "Scheduling"
    "ALARM_ARMING" -> "Alarm setup"
    "DUE_START" -> "Due-time admission"
    "CAPACITY_ADMISSION" -> "Live capacity admission"
    "FOREGROUND_SERVICE" -> "Android foreground service"
    "CAPTURE" -> "Stream capture"
    "FINALIZATION" -> "Saving the recording"
    "RECOVERY" -> "Recovery"
    "PLAYBACK_PREEMPTION" -> "Playback preemption"
    "USER_ACTION" -> "User action"
    else -> "Recording"
}

@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    openRecordingsRequestId: Long = 0L,
    onOpenRecordingsRequestConsumed: () -> Unit = {},
    onPlaybackStarted: () -> Unit = {},
    onOpenDetails: (DownloadDetailsNavigation) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onSavedTvPlaybackStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val playbackScope = rememberCoroutineScope()
    val recordings by services.liveRecordingRepository.recordings.collectAsState(initial = emptyList())
    val visibleRecordings = recordings
    var selectedDownloadFilter by rememberSaveable {
        mutableStateOf(DownloadLibraryFilter.ALL)
    }
    val catalogListState = rememberContextLazyListState(selectedDownloadFilter.name)

    fun selectDownloadFilter(filter: DownloadLibraryFilter) {
        if (filter == selectedDownloadFilter) return
        selectedDownloadFilter = filter
        playbackScope.launch { catalogListState.scrollToItem(0) }
    }

    fun openRecording(recording: LiveRecording) {
        val reference = recording.localReference ?: return
        val start = recording.actualStartEpochSeconds ?: recording.startEpochSeconds
        val end = recording.deadlineEpochSeconds
        if (end <= start) return
        playbackScope.launch {
            services.playbackSessionController.activateCatchUp(
                PlaybackTarget.CatchUp(
                    sourceId = SourceId(recording.sourceId),
                    channelId = recording.channelId,
                    programId = recording.recordingId,
                    title = recording.title,
                    startEpochSeconds = start,
                    endEpochSeconds = end,
                    localMediaUri = reference,
                ),
            )
            services.playbackSessionController.enterFullscreen()
            onSavedTvPlaybackStarted()
        }
    }

    fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + application.packageName)),
            )
        }
    }

    LaunchedEffect(openRecordingsRequestId) {
        if (openRecordingsRequestId > 0L) {
            selectDownloadFilter(DownloadLibraryFilter.RECORDINGS)
            onOpenRecordingsRequestConsumed()
        }
    }
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val source by activeSourceFlow.collectAsState(initial = null)

    val activeSource = source
    if (selectedDownloadFilter == DownloadLibraryFilter.RECORDINGS) {
        Column(modifier = modifier.fillMaxSize()) {
            Text(
                text = "Downloads / Offline",
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DownloadLibraryFilterBar(
                selectedFilter = selectedDownloadFilter,
                onFilterSelected = ::selectDownloadFilter,
            )
            LiveRecordingsCatalog(
                recordings = visibleRecordings,
                onCancel = services.liveRecordingScheduler::cancel,
                onRemove = services.liveRecordingRemovalManager::remove,
                onRetryDelete = services.liveRecordingRemovalManager::retryPendingDelete,
                onRetrySchedule = { recording -> services.liveRecordingScheduler.schedule(recording) },
                onEnableExactAlarms = ::requestExactAlarmAccess,
                onOpen = ::openRecording,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    if (activeSource == null) {
        Column(modifier = modifier.fillMaxSize()) {
            Text(
                text = "Downloads / Offline",
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            DownloadLibraryFilterBar(
                selectedFilter = selectedDownloadFilter,
                onFilterSelected = ::selectDownloadFilter,
            )
            if (selectedDownloadFilter == DownloadLibraryFilter.ALL && recordings.isNotEmpty()) {
                LiveRecordingsCatalog(
                    recordings = recordings,
                    onCancel = services.liveRecordingScheduler::cancel,
                    onRemove = services.liveRecordingRemovalManager::remove,
                    onRetryDelete = services.liveRecordingRemovalManager::retryPendingDelete,
                    onRetrySchedule = { recording -> services.liveRecordingScheduler.schedule(recording) },
                    onEnableExactAlarms = ::requestExactAlarmAccess,
                    onOpen = ::openRecording,
                    modifier = Modifier.weight(1f),
                )
            } else {
                OwnPlayFeaturePlaceholder(
                    title = "Downloads",
                    message = "Add or select a source in Settings to manage offline media.",
                    modifier = Modifier.weight(1f),
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                )
            }
        }
        return
    }

    val downloadsFlow = remember(services.downloadRepository, activeSource.sourceId) {
        services.downloadRepository.observeDownloads(activeSource.sourceId)
    }
    val activeDownloads by downloadsFlow.collectAsState(initial = emptyList())
    val allDownloadsFlow = remember(services.downloadRepository) {
        services.downloadRepository.observeAllDownloads()
    }
    val allDownloads by allDownloadsFlow.collectAsState(initial = emptyList())
    val downloads = remember(activeDownloads, allDownloads, activeSource.sourceId) {
        val otherLocal = allDownloads.filter { item ->
            item.sourceId != activeSource.sourceId &&
                !item.localReference.isNullOrBlank() &&
                item.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.MISSING)
        }
        (activeDownloads + otherLocal).distinctBy { it.downloadId.value }
    }
    val importFolderUri by services.externalDownloadImportManager.folderUri.collectAsState(initial = null)
    val importScope = rememberCoroutineScope()
    var importBusy by remember(activeSource.sourceId) { mutableStateOf(false) }
    var importMessage by remember(activeSource.sourceId) { mutableStateOf<String?>(null) }
    var importIssues by remember(activeSource.sourceId) {
        mutableStateOf<List<ExternalDownloadImportIssue>>(emptyList())
    }

    fun applyImportResult(result: ExternalDownloadImportResult) {
        importIssues = result.unresolvedFiles
        importMessage = externalImportResultMessage(result)
    }

    fun scanImportFolder() {
        if (importBusy) return
        importBusy = true
        importScope.launch {
            try {
                val result = services.externalDownloadImportManager.scan(activeSource.sourceId)
                applyImportResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "The folder scan could not be completed."
            } finally {
                importBusy = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        importMessage = null
        importScope.launch {
            try {
                val granted = services.externalDownloadImportManager.grantFolder(uri)
                importMessage = if (granted) {
                    "Folder linked with read-only access. Scanning…"
                } else {
                    "Choose a supported local-media folder and allow read access."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "OwnPlay could not keep access to that folder."
            } finally {
                importBusy = false
            }
        }
    }

    LaunchedEffect(activeSource.sourceId, importFolderUri) {
        if (importFolderUri != null) {
            importBusy = true
            try {
                val result = services.externalDownloadImportManager.scan(activeSource.sourceId)
                applyImportResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "The folder scan could not be completed."
            } finally {
                importBusy = false
            }
        }
    }

    fun resolveImportIssue(
        issue: ExternalDownloadImportIssue,
        match: ExternalDownloadImportMatch,
    ) {
        if (importBusy) return
        importBusy = true
        importScope.launch {
            try {
                val imported = services.externalDownloadImportManager.importSelection(
                    sourceId = activeSource.sourceId,
                    file = issue.file,
                    selected = match,
                )
                val refreshed = services.externalDownloadImportManager.scan(activeSource.sourceId)
                applyImportResult(refreshed)
                if (!imported) {
                    importMessage = "That local file could not be linked because its catalog state changed."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importMessage = "The selected local file could not be linked."
            } finally {
                importBusy = false
            }
        }
    }

    var episodeContexts by remember(activeSource.sourceId) {
        mutableStateOf<Map<String, DownloadEpisodeContext>>(emptyMap())
    }

    LaunchedEffect(downloads) {
        val resolved = linkedMapOf<String, DownloadEpisodeContext>()
        downloads
            .filter { it.mediaKind == DownloadMediaKind.EPISODE }
            .forEach { item ->
                services.downloadPresentationMetadataResolver
                    .resolveEpisodeContext(item)
                    ?.let { context -> resolved[item.downloadId.value] = context }
            }
        episodeContexts = resolved
    }

    Column(modifier = modifier.fillMaxSize()) {
        DownloadLibraryFilterBar(
            selectedFilter = selectedDownloadFilter,
            onFilterSelected = ::selectDownloadFilter,
        )
        Text(
            "Local downloads from all sources · Active transfers from the selected source",
            color = OwnPlayColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        DownloadCatalog(
            downloads = downloads,
            selectedFilter = selectedDownloadFilter,
            listState = catalogListState,
            recordings = recordings,
            onCancelRecording = services.liveRecordingScheduler::cancel,
            onRemoveRecording = services.liveRecordingRemovalManager::remove,
            onRetryDeleteRecording = services.liveRecordingRemovalManager::retryPendingDelete,
            onRetryScheduleRecording = { recording -> services.liveRecordingScheduler.schedule(recording) },
            onEnableExactAlarms = ::requestExactAlarmAccess,
            onOpenRecording = ::openRecording,
            episodeContexts = episodeContexts,
            repository = services.downloadRepository,
            playbackSessionController = services.playbackSessionController,
            onPlaybackStarted = onPlaybackStarted,
            onOpenDetails = onOpenDetails,
            onOpenLibrary = onOpenLibrary,
            importFolderLinked = importFolderUri != null,
            importBusy = importBusy,
            importMessage = importMessage,
            importIssues = importIssues,
            onChooseImportFolder = { folderPicker.launch(null) },
            onScanImportFolder = ::scanImportFolder,
            onResolveImportIssue = ::resolveImportIssue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DownloadLibraryFilterBar(
    selectedFilter: DownloadLibraryFilter,
    onFilterSelected: (DownloadLibraryFilter) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DownloadLibraryFilter.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelected(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun LiveRecordingsCatalog(
    recordings: List<LiveRecording>,
    onCancel: (String) -> Boolean,
    onRemove: suspend (String, Boolean) -> Boolean,
    onRetryDelete: suspend (String) -> Boolean,
    onRetrySchedule: (LiveRecording) -> Unit,
    onEnableExactAlarms: () -> Unit,
    onOpen: (LiveRecording) -> Unit,
    modifier: Modifier,
) {
    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        liveRecordingItems(
            recordings = recordings,
            onCancel = onCancel,
            onRemove = onRemove,
            onRetryDelete = onRetryDelete,
            onRetrySchedule = onRetrySchedule,
            onEnableExactAlarms = onEnableExactAlarms,
            onOpen = onOpen,
            showEmptyMessage = true,
        )
    }
}

private fun LazyListScope.liveRecordingItems(
    recordings: List<LiveRecording>,
    onCancel: (String) -> Boolean,
    onRemove: suspend (String, Boolean) -> Boolean,
    onRetryDelete: suspend (String) -> Boolean,
    onRetrySchedule: (LiveRecording) -> Unit,
    onEnableExactAlarms: () -> Unit,
    onOpen: (LiveRecording) -> Unit,
    showEmptyMessage: Boolean,
) {
    val dateFormatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    item {
        Text(
            text = "Recordings",
            color = OwnPlayColors.TextPrimary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
    if (recordings.isEmpty() && showEmptyMessage) {
        item {
            Text(
                text = "No recordings yet. Record or schedule a program from the Live EPG.",
                color = OwnPlayColors.TextSecondary,
            )
        }
    }
    items(recordings, key = LiveRecording::recordingId) { recording ->
        var showRemoveDialog by remember(recording.recordingId) { mutableStateOf(false) }
        var deleteLocalFile by remember(recording.recordingId) { mutableStateOf(false) }
        var removeBusy by remember(recording.recordingId) { mutableStateOf(false) }
        var removeError by remember(recording.recordingId) { mutableStateOf<String?>(null) }
        val removalScope = rememberCoroutineScope()
        val canDeleteLocalFile = LiveRecordingRemovalPolicy.canDeleteLocalFile(recording)

        if (showRemoveDialog) {
            DownloadRemovalDialog(
                canDeleteLocalFile = canDeleteLocalFile,
                deleteLocalFile = deleteLocalFile,
                busy = removeBusy,
                onDeleteLocalFileChange = { deleteLocalFile = it },
                onConfirm = {
                    if (!removeBusy) {
                        removeBusy = true
                        removeError = null
                        removalScope.launch {
                            val removed = onRemove(
                                recording.recordingId,
                                deleteLocalFile && canDeleteLocalFile,
                            )
                            removeBusy = false
                            if (removed) {
                                showRemoveDialog = false
                                deleteLocalFile = false
                            } else {
                                removeError = "The recording could not be removed."
                            }
                        }
                    }
                },
                onDismiss = {
                    if (!removeBusy) {
                        showRemoveDialog = false
                        deleteLocalFile = false
                        removeError = null
                    }
                },
                title = "Remove recording from OwnPlay?",
                message = "This removes the recording entry. The local recording file is kept unless you explicitly choose to delete it.",
                deleteLocalFileLabel = "Delete the local recording file too (OwnPlay Downloads/Recordings)",
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = OwnPlayShapes.Medium,
            color = OwnPlayColors.SurfaceRaised,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = recording.title,
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${recording.channelName} · ${dateFormatter.format(Date(recording.startEpochSeconds * 1_000L))}",
                    color = OwnPlayColors.TextSecondary,
                )
                Text(
                    text = when (recording.status) {
                        LiveRecordingStatus.SCHEDULED -> when (recording.scheduleArmedState) {
                            LiveRecordingScheduleArmedStates.ARMED -> "Scheduled · exact alarm armed"
                            LiveRecordingScheduleArmedStates.NOT_ARMED_PERMISSION ->
                                recording.safeError ?: "Scheduled · exact-alarm permission required"
                            LiveRecordingScheduleArmedStates.NOT_ARMED_ERROR ->
                                recording.safeError ?: "Scheduled · alarm could not be armed"
                            else -> recording.safeError ?: "Scheduled · alarm state not confirmed"
                        }
                        LiveRecordingStatus.STARTING -> "Preparing / connecting · waiting for media"
                        LiveRecordingStatus.RECORDING -> "Recording"
                        LiveRecordingStatus.FINALIZING -> "Saving recording…"
                        LiveRecordingStatus.COMPLETED -> "Saved in OwnPlay Downloads/Recordings"
                        LiveRecordingStatus.PARTIAL -> "Partial recording saved in OwnPlay Downloads/Recordings"
                        LiveRecordingStatus.FAILED -> recording.safeError ?: "Recording failed"
                        LiveRecordingStatus.CANCELLED -> "Cancelled"
                        LiveRecordingStatus.DELETE_PENDING -> recording.safeError ?: "Local file removal is pending."
                    },
                    color = if (
                        recording.status == LiveRecordingStatus.FAILED ||
                        (recording.status == LiveRecordingStatus.SCHEDULED && recording.safeError != null)
                    ) {
                        OwnPlayColors.Error
                    } else {
                        OwnPlayColors.TextSecondary
                    },
                )
                if (recording.status == LiveRecordingStatus.SCHEDULED ||
                    recording.status == LiveRecordingStatus.STARTING ||
                    recording.status == LiveRecordingStatus.RECORDING
                ) {
                    TextButton(
                        onClick = { onCancel(recording.recordingId) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (recording.status in setOf(LiveRecordingStatus.STARTING, LiveRecordingStatus.RECORDING)) "Stop & Save" else "Cancel recording")
                    }
                }
                val failureLabel = recording.failureReasonCode?.let(::safeFailureReasonLabel)
                if (failureLabel != null) {
                    Text("Reason · $failureLabel", color = OwnPlayColors.TextSecondary)
                    recording.failureStage?.let { stage ->
                        Text("Stage · ${safeFailureStageLabel(stage)}", color = OwnPlayColors.TextMuted)
                    }
                    recording.failureAtEpochMs?.let { at ->
                        val label = if (recording.status == LiveRecordingStatus.FAILED) "Failed" else "Updated"
                        Text("$label · ${dateFormatter.format(Date(at))}", color = OwnPlayColors.TextMuted)
                    }
                } else if (
                    recording.status == LiveRecordingStatus.FAILED &&
                    !recording.safeError.isNullOrBlank()
                ) {
                    Text(recording.safeError.orEmpty(), color = OwnPlayColors.Error)
                }
                if (recording.progressBytes > 0L && recording.status in setOf(
                    LiveRecordingStatus.STARTING,
                    LiveRecordingStatus.RECORDING,
                    LiveRecordingStatus.FINALIZING,
                    LiveRecordingStatus.PARTIAL,
                    LiveRecordingStatus.COMPLETED,
                )) {
                    Text("${formatBytes(recording.progressBytes)} received", color = OwnPlayColors.TextMuted)
                }
                if (
                    (recording.status == LiveRecordingStatus.COMPLETED ||
                        recording.status == LiveRecordingStatus.PARTIAL) &&
                    !recording.localReference.isNullOrBlank()
                ) {
                    TextButton(
                        onClick = { onOpen(recording) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Open offline") }
                }
                if (
                    recording.status == LiveRecordingStatus.SCHEDULED &&
                    recording.scheduleArmedState != LiveRecordingScheduleArmedStates.ARMED
                ) {
                    if (recording.scheduleArmedState == LiveRecordingScheduleArmedStates.NOT_ARMED_ERROR) {
                        TextButton(
                            onClick = { onRetrySchedule(recording) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Retry alarm setup") }
                    } else {
                        TextButton(
                            onClick = onEnableExactAlarms,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Enable exact alarms")
                        }
                    }
                }
                if (recording.status == LiveRecordingStatus.DELETE_PENDING) {
                    TextButton(
                        enabled = !removeBusy,
                        onClick = {
                            if (!removeBusy) {
                                removeBusy = true
                                removeError = null
                                removalScope.launch {
                                    val removed = onRetryDelete(recording.recordingId)
                                    removeBusy = false
                                    if (!removed) removeError = "Local file removal is still pending."
                                }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (removeBusy) "Retrying…" else "Retry removal") }
                }
                if (LiveRecordingRemovalPolicy.canRemove(recording.status)) {
                    TextButton(
                        enabled = !removeBusy,
                        onClick = {
                            deleteLocalFile = false
                            removeError = null
                            showRemoveDialog = true
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Remove")
                    }
                }
                removeError?.let {
                    Text(it, color = OwnPlayColors.Error)
                }
            }
        }
    }
}

@Composable
private fun DownloadCatalog(
    downloads: List<DownloadItem>,
    selectedFilter: DownloadLibraryFilter,
    listState: LazyListState,
    recordings: List<LiveRecording>,
    onCancelRecording: (String) -> Boolean,
    onRemoveRecording: suspend (String, Boolean) -> Boolean,
    onRetryDeleteRecording: suspend (String) -> Boolean,
    onRetryScheduleRecording: (LiveRecording) -> Unit,
    onEnableExactAlarms: () -> Unit,
    onOpenRecording: (LiveRecording) -> Unit,
    episodeContexts: Map<String, DownloadEpisodeContext>,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    onPlaybackStarted: () -> Unit,
    onOpenDetails: (DownloadDetailsNavigation) -> Unit,
    onOpenLibrary: () -> Unit,
    importFolderLinked: Boolean,
    importBusy: Boolean,
    importMessage: String?,
    importIssues: List<ExternalDownloadImportIssue>,
    onChooseImportFolder: () -> Unit,
    onScanImportFolder: () -> Unit,
    onResolveImportIssue: (ExternalDownloadImportIssue, ExternalDownloadImportMatch) -> Unit,
    modifier: Modifier,
) {
    val includeAll = selectedFilter == DownloadLibraryFilter.ALL
    val movies = downloads.filter { includeAll || selectedFilter == DownloadLibraryFilter.MOVIES }
        .filter { it.mediaKind == DownloadMediaKind.MOVIE }
    val episodes = downloads.filter { includeAll || selectedFilter == DownloadLibraryFilter.SERIES }
        .filter { it.mediaKind == DownloadMediaKind.EPISODE }
    val catchUps = downloads.filter { includeAll || selectedFilter == DownloadLibraryFilter.CATCH_UP }
        .filter { it.mediaKind == DownloadMediaKind.CATCH_UP }
    val visibleItems = movies + episodes + catchUps
    val waitingOrder = downloads
        .filter { it.status == DownloadStatus.WAITING_FOR_WIFI || it.status == DownloadStatus.QUEUED }
        .sortedWith(compareBy<DownloadItem> { it.createdAtEpochMs }.thenBy { it.downloadId.value })
        .mapIndexed { index, item -> item.downloadId.value to index + 1 }
        .toMap()
    val seriesGroups = episodes
        .groupBy { episodeContexts[it.downloadId.value]?.seriesTitle ?: "Other episodes" }
        .entries
        .sortedWith(
            compareBy<Map.Entry<String, List<DownloadItem>>> { it.key.lowercase(Locale.ROOT) }
                .thenBy { it.key },
        )

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "Downloads / Offline",
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (visibleItems.isEmpty() && (!includeAll || recordings.isEmpty())) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "No downloads yet. Download Movies or Series from Library, or save eligible archived programs from Live.",
                        color = OwnPlayColors.TextMuted,
                    )
                    FilledTonalButton(
                        onClick = onOpenLibrary,
                        shape = OwnPlayShapes.Medium,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Browse Library")
                    }
                }
            }
        }

        if (includeAll && recordings.isNotEmpty()) {
            liveRecordingItems(
                recordings = recordings,
                onCancel = onCancelRecording,
                onRemove = onRemoveRecording,
                onRetryDelete = onRetryDeleteRecording,
                onRetrySchedule = onRetryScheduleRecording,
                onEnableExactAlarms = onEnableExactAlarms,
                onOpen = onOpenRecording,
                showEmptyMessage = false,
            )
        }

        if (catchUps.isNotEmpty()) {
            item { DownloadSectionTitle("Catch-up") }
            items(catchUps.sortedByDescending { it.createdAtEpochMs }, key = { it.downloadId.value }) { item ->
                DownloadRow(
                    item = item,
                    episodeContext = null,
                    queuePosition = waitingOrder[item.downloadId.value],
                    repository = repository,
                    playbackSessionController = playbackSessionController,
                    onPlaybackStarted = onPlaybackStarted,
                    onOpenDetails = onOpenDetails,
                )
            }
        }

        if (movies.isNotEmpty()) {
            item { DownloadSectionTitle("Movies") }
            items(movies, key = { it.downloadId.value }) { item ->
                DownloadRow(
                    item = item,
                    episodeContext = null,
                    queuePosition = waitingOrder[item.downloadId.value],
                    repository = repository,
                    playbackSessionController = playbackSessionController,
                    onPlaybackStarted = onPlaybackStarted,
                    onOpenDetails = onOpenDetails,
                )
            }
        }

        if (seriesGroups.isNotEmpty()) {
            item { DownloadSectionTitle("Series") }
            seriesGroups.forEach { (seriesTitle, seriesItems) ->
                item(key = "series:$seriesTitle") {
                    Text(
                        text = seriesTitle,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val seasonGroups = seriesItems
                    .groupBy { episodeContexts[it.downloadId.value]?.seasonNumber }
                    .entries
                    .sortedBy { it.key ?: Int.MAX_VALUE }
                seasonGroups.forEach { (seasonNumber, seasonItems) ->
                    item(key = "season:$seriesTitle:${seasonNumber ?: "unknown"}") {
                        Text(
                            text = seasonNumber?.let { "Season ${it.toString().padStart(2, '0')}" }
                                ?: "Season unknown",
                            color = OwnPlayColors.TextSecondary,
                        )
                    }
                    items(
                        items = seasonItems.sortedWith(
                            compareBy<DownloadItem> {
                                episodeContexts[it.downloadId.value]?.episodeNumber ?: Int.MAX_VALUE
                            }.thenBy { it.downloadId.value },
                        ),
                        key = { it.downloadId.value },
                    ) { item ->
                        DownloadRow(
                            item = item,
                            episodeContext = episodeContexts[item.downloadId.value],
                            queuePosition = waitingOrder[item.downloadId.value],
                            repository = repository,
                            playbackSessionController = playbackSessionController,
                            onPlaybackStarted = onPlaybackStarted,
                    onOpenDetails = onOpenDetails,
                        )
                    }
                }
            }
        }

        item {
            LocalImportSection(
                importFolderLinked = importFolderLinked,
                importBusy = importBusy,
                importMessage = importMessage,
                importIssues = importIssues,
                onChooseImportFolder = onChooseImportFolder,
                onScanImportFolder = onScanImportFolder,
                onResolveImportIssue = onResolveImportIssue,
            )
        }
    }
}

@Composable
private fun LocalImportSection(
    importFolderLinked: Boolean,
    importBusy: Boolean,
    importMessage: String?,
    importIssues: List<ExternalDownloadImportIssue>,
    onChooseImportFolder: () -> Unit,
    onScanImportFolder: () -> Unit,
    onResolveImportIssue: (ExternalDownloadImportIssue, ExternalDownloadImportMatch) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Import existing files",
            color = OwnPlayColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Choose a local-media folder. OwnPlay keeps read-only access and never moves, renames, rewrites, or deletes files while scanning.",
            color = OwnPlayColors.TextSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                enabled = !importBusy,
                onClick = if (importFolderLinked) onScanImportFolder else onChooseImportFolder,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        importBusy -> "Scanning…"
                        importFolderLinked -> "Scan folder"
                        else -> "Choose folder"
                    },
                )
            }
            if (importFolderLinked) {
                TextButton(
                    enabled = !importBusy,
                    onClick = onChooseImportFolder,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Change folder")
                }
            }
        }
        importMessage?.let { message ->
            Text(
                text = message,
                color = OwnPlayColors.TextSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        importIssues.forEach { issue ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = OwnPlayShapes.Medium,
                color = OwnPlayColors.SurfaceRaised,
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = issue.file.displayName,
                        color = OwnPlayColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                    )
                    when (issue.kind) {
                        ExternalDownloadImportIssueKind.UNMATCHED ->
                            Text("Local file · no safe catalog match", color = OwnPlayColors.TextSecondary)
                        ExternalDownloadImportIssueKind.CONFLICT ->
                            Text("Local file · existing download state must be resolved first", color = OwnPlayColors.TextSecondary)
                        ExternalDownloadImportIssueKind.AMBIGUOUS -> {
                            Text("Choose the correct catalog item:", color = OwnPlayColors.TextSecondary)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                issue.candidates.forEach { candidate ->
                                    TextButton(
                                        enabled = !importBusy,
                                        onClick = { onResolveImportIssue(issue, candidate) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(candidate.title, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun externalImportResultMessage(result: ExternalDownloadImportResult): String {
    if (result.accessRequired) return "Folder read access needs to be selected again."
    val scanPrefix = when (result.scanStatus) {
        ExternalDownloadScanStatus.COMPLETE -> "Scan complete."
        ExternalDownloadScanStatus.TRUNCATED -> "Partial scan: the safe document/depth limit was reached."
        ExternalDownloadScanStatus.PERMISSION_ERROR -> "Partial scan: access was denied for part of the selected folder."
        ExternalDownloadScanStatus.ERROR -> "Partial scan: part of the selected folder could not be read."
    }
    val details = buildList {
        if (result.imported > 0) add("Imported " + result.imported + ".")
        if (result.alreadyTracked > 0) add(result.alreadyTracked.toString() + " already tracked.")
        if (result.ambiguous > 0) add(result.ambiguous.toString() + " need your selection.")
        if (result.unmatched > 0) add(result.unmatched.toString() + " unmatched.")
        if (result.conflicts > 0) add(result.conflicts.toString() + " conflicts.")
        if (result.discoveredVideoFiles == 0) add("No supported video files found.")
    }
    return (listOf(scanPrefix) + details).joinToString(" ")
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    episodeContext: DownloadEpisodeContext?,
    queuePosition: Int?,
    repository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    onPlaybackStarted: () -> Unit,
    onOpenDetails: (DownloadDetailsNavigation) -> Unit,
) {
    val handler = remember(repository) { DownloadActionHandler(repository) }
    val scope = rememberCoroutineScope()
    var busy by remember(item.downloadId) { mutableStateOf(false) }
    var actionFailed by remember(item.downloadId) { mutableStateOf(false) }
    var showRemoveDialog by remember(item.downloadId) { mutableStateOf(false) }
    var deleteLocalFile by remember(item.downloadId) { mutableStateOf(false) }
    val primary = DownloadUserActionPolicy.primary(item.status)
    val canDeleteLocalFile = DownloadRemovalPolicy.canDeleteLocalFile(item)

    fun play(offline: Boolean) {
        if (item.mediaKind == DownloadMediaKind.CATCH_UP) {
            val identity = CatchUpDownloadIdentity.decode(item.sourceContentIdentity) ?: run {
                actionFailed = true
                return
            }
            scope.launch {
                playbackSessionController.activateCatchUp(
                    PlaybackTarget.CatchUp(
                        sourceId = item.sourceId,
                        channelId = identity.channelId,
                        programId = identity.programId,
                        title = item.title,
                        startEpochSeconds = identity.startEpochSeconds,
                        endEpochSeconds = identity.endEpochSeconds,
                        offlineDownloadId = item.downloadId.value.takeIf { offline },
                    ),
                )
                onPlaybackStarted()
            }
            return
        }
        val target = when (item.mediaKind) {
            DownloadMediaKind.MOVIE -> PlaybackTarget.Movie(
                sourceId = item.sourceId,
                movieId = item.contentId,
                offlineDownloadId = item.downloadId.value.takeIf { offline },
            )
            DownloadMediaKind.EPISODE -> PlaybackTarget.Episode(
                sourceId = item.sourceId,
                episodeId = item.contentId,
                offlineDownloadId = item.downloadId.value.takeIf { offline },
                seriesId = episodeContext?.seriesId,
            )
            DownloadMediaKind.CATCH_UP -> error("Handled above")
        }
        scope.launch {
            playbackSessionController.activateLibraryMedia(target)
            onPlaybackStarted()
        }
    }

    fun dispatch(action: DownloadUserAction) {
        if (busy) return
        if (action == DownloadUserAction.PLAY_OFFLINE) {
            play(offline = true)
            return
        }
        busy = true
        actionFailed = false
        scope.launch {
            try {
                val request = DownloadRequest(
                    sourceId = item.sourceId,
                    mediaKind = item.mediaKind,
                    contentId = item.contentId,
                    title = item.title,
                    sourceContentIdentity = item.sourceContentIdentity,
                )
                actionFailed = !handler.execute(action, request, item.downloadId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                actionFailed = true
            } finally {
                busy = false
            }
        }
    }

    if (showRemoveDialog) {
        DownloadRemovalDialog(
            canDeleteLocalFile = canDeleteLocalFile,
            deleteLocalFile = deleteLocalFile,
            busy = busy,
            onDeleteLocalFileChange = { deleteLocalFile = it },
            onConfirm = {
                val action = if (deleteLocalFile && canDeleteLocalFile) {
                    DownloadUserAction.REMOVE
                } else {
                    DownloadUserAction.FORGET
                }
                showRemoveDialog = false
                deleteLocalFile = false
                dispatch(action)
            },
            onDismiss = {
                showRemoveDialog = false
                deleteLocalFile = false
            },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OwnPlayShapes.Medium,
        color = OwnPlayColors.SurfaceRaised,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = episodeContext?.let { context ->
                    "E${context.episodeNumber.toString().padStart(2, '0')} · ${item.title}"
                } ?: item.title,
                color = OwnPlayColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = downloadStatusText(item, queuePosition),
                color = if (item.status == DownloadStatus.FAILED || item.status == DownloadStatus.MISSING) {
                    OwnPlayColors.Error
                } else {
                    OwnPlayColors.TextSecondary
                },
            )
            if (item.status == DownloadStatus.DOWNLOADING) {
                item.totalBytes
                    ?.takeIf { it > 0L }
                    ?.let { totalBytes ->
                        val progress = (item.bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
            }
            primary?.let { action ->
                FilledTonalButton(
                    enabled = !busy,
                    onClick = { dispatch(action) },
                    shape = OwnPlayShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        when {
                            busy -> "Working…"
                            action == DownloadUserAction.RETRY && item.status == DownloadStatus.MISSING ->
                                "Download again"
                            action == DownloadUserAction.PLAY_OFFLINE -> "Play offline"
                            action == DownloadUserAction.DOWNLOAD -> "Download"
                            action == DownloadUserAction.PAUSE -> "Pause"
                            action == DownloadUserAction.RESUME -> "Restart from beginning"
                            action == DownloadUserAction.CANCEL -> "Cancel"
                            action == DownloadUserAction.RETRY -> "Retry"
                            else -> "Open"
                        },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        onOpenDetails(
                            DownloadDetailsNavigation(
                                downloadId = item.downloadId,
                                sourceId = item.sourceId,
                                mediaKind = item.mediaKind,
                                contentId = item.contentId,
                            ),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Details")
                }
                if (item.status == DownloadStatus.MISSING) {
                    TextButton(
                        enabled = !busy,
                        onClick = { play(offline = false) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Play online")
                    }
                }
                if (DownloadUserActionPolicy.canRemove(item.status)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            deleteLocalFile = false
                            showRemoveDialog = true
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Remove")
                    }
                }
            }
            if (actionFailed) {
                Text(
                    text = "The download action could not be completed.",
                    color = OwnPlayColors.Error,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadSectionTitle(title: String) {
    Text(
        text = title,
        color = OwnPlayColors.TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun downloadStatusText(
    item: DownloadItem,
    queuePosition: Int?,
): String = when (item.status) {
    DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi" + queuePosition?.let { " · Queue $it" }.orEmpty()
    DownloadStatus.QUEUED -> "Queued" + queuePosition?.let { " · Queue $it" }.orEmpty()
    DownloadStatus.DOWNLOADING -> item.totalBytes?.let { total ->
        val percent = (item.bytesDownloaded.toDouble() / total * 100.0).toInt().coerceIn(0, 100)
        "Downloading · $percent%"
    } ?: "Downloading · ${formatBytes(item.bytesDownloaded)}"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> if (item.origin == DownloadOrigin.IMPORTED_EXTERNAL) {
        "Imported · ${formatBytes(item.verifiedBytes ?: item.bytesDownloaded)}"
    } else {
        "Downloaded · ${formatBytes(item.verifiedBytes ?: item.bytesDownloaded)}"
    }
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.MISSING -> "Not available"
    DownloadStatus.CANCELED -> "Canceled"
    DownloadStatus.UNKNOWN -> "Needs attention"
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
        value >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.ROOT, value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, value / (1024.0 * 1024.0))
        value >= 1024L -> "%.1f KB".format(Locale.ROOT, value / 1024.0)
        else -> "$value B"
    }
}
