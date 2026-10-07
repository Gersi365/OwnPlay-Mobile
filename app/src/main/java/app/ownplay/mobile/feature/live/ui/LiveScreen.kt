package app.ownplay.mobile.feature.live.ui

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.ownplay.mobile.design.rememberContextLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ownplay.mobile.MainActivity
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.design.OwnPlayFeaturePlaceholder
import app.ownplay.mobile.design.OwnPlayMobileTopBar
import app.ownplay.mobile.design.OwnPlayShapes
import app.ownplay.mobile.design.ProviderCategoryDisplayPolicy
import app.ownplay.mobile.feature.live.domain.LiveCatchUpCatalog
import app.ownplay.mobile.feature.live.domain.LiveCatchUpPolicy
import app.ownplay.mobile.feature.live.domain.LiveCatchUpProgram
import app.ownplay.mobile.feature.live.domain.LiveCatchUpRepository
import app.ownplay.mobile.feature.live.domain.LiveGuidePolicy
import app.ownplay.mobile.feature.live.domain.LiveGuideRepository
import app.ownplay.mobile.feature.live.domain.LiveNowNext
import app.ownplay.mobile.feature.live.domain.LiveOrganizationChannel
import app.ownplay.mobile.feature.live.domain.LiveProgram
import app.ownplay.mobile.feature.live.domain.LiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.ProviderLiveCatalogSnapshot
import app.ownplay.mobile.feature.live.domain.LiveRecording
import app.ownplay.mobile.feature.live.domain.LiveRecordingAction
import app.ownplay.mobile.feature.live.domain.LiveRecordingPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingStopPolicy
import app.ownplay.mobile.feature.live.domain.LiveRecordingStatus
import app.ownplay.mobile.downloads.domain.CatchUpDownloadIdentity
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadRequest
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.feature.live.data.LiveRecordingScheduleResult
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.ui.ArtworkPresentation
import app.ownplay.mobile.feature.library.ui.LibraryArtwork
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferences
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackPresentation
import app.ownplay.mobile.feature.playback.domain.PlaybackReadiness
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.playback.domain.PlaybackTarget
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoSurface
import app.ownplay.mobile.feature.settings.domain.DisplayPreferences
import app.ownplay.mobile.sources.domain.SourceSummary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onReturnToDownloads: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val playbackState by services.playbackSessionController.state.collectAsState()
    val localPlaybackScope = rememberCoroutineScope()
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val activeSource by activeSourceFlow.collectAsState(initial = null)
    val displayPreferences by services.displayPreferencesRepository.preferences.collectAsState(
        initial = DisplayPreferences(),
    )
    val sourceStateHolder = rememberSaveableStateHolder()

    val savedTvTarget = playbackState.target as? PlaybackTarget.CatchUp
    if (
        savedTvTarget?.let { it.localMediaUri != null || it.offlineDownloadId != null } == true &&
        playbackState.presentation == PlaybackPresentation.FULLSCREEN
    ) {
        CatchUpFullscreenPresentation(
            target = savedTvTarget,
            playbackState = playbackState,
            playbackSessionController = services.playbackSessionController,
            playbackEngine = services.playbackEngine,
            onPictureInPicture = {
                (context.findLiveActivity() as? MainActivity)?.requestOwnPlayPictureInPicture()
            },
            onRetry = {
                localPlaybackScope.launch { services.playbackSessionController.retryActiveTarget() }
            },
            onDismiss = {
                services.playbackSessionController.clear()
                onReturnToDownloads()
            },
        )
        return
    }

    val source = activeSource
    if (source == null) {
        OwnPlayFeaturePlaceholder(
            title = "Live",
            message = "Add or select a source in Settings to start watching live channels.",
            modifier = modifier,
            actionLabel = "Open Settings",
            onAction = onOpenSettings,
        )
        return
    }

    var initialRefreshError by remember(source.sourceId) { mutableStateOf<String?>(null) }
    var initialRefreshInProgress by remember(source.sourceId) { mutableStateOf(false) }
    LaunchedEffect(source.sourceId, source.lastSuccessfulRefreshAtEpochMs) {
        if (source.enabled && source.lastSuccessfulRefreshAtEpochMs == null) {
            initialRefreshError = null
            initialRefreshInProgress = true
            try {
                val result = services.sourceRepository.refreshSource(source.sourceId)
                initialRefreshError = when (result) {
                    is app.ownplay.mobile.sources.domain.SourceRefreshResult.Failure ->
                        result.safeMessage ?: "Source refresh failed."
                    is app.ownplay.mobile.sources.domain.SourceRefreshResult.Partial ->
                        "Source refreshed partially. Some catalog sections are using last-good data."
                    else -> null
                }
            } finally {
                initialRefreshInProgress = false
            }
        }
    }

    sourceStateHolder.SaveableStateProvider(
        key = "live-source:" + source.sourceId.value,
    ) {
        LiveSourceScreen(
            source = source,
            repository = services.liveOrganizationRepository,
            guideRepository = services.liveGuideRepository,
            catchUpRepository = services.liveCatchUpRepository,
            liveRecordingRepository = services.liveRecordingRepository,
            liveRecordingScheduler = services.liveRecordingScheduler,
            downloadRepository = services.downloadRepository,
            playbackSessionController = services.playbackSessionController,
            playbackPreferencesRepository = services.playbackPreferencesRepository,
            playbackEngine = services.playbackEngine,
            artworkLoader = services.libraryArtworkLoader,
            compactMediaRows = displayPreferences.compactMediaRows,
            showChannelLogos = displayPreferences.showChannelLogos,
            preferTvgName = displayPreferences.preferTvgName,
            hideChannelPrefix = displayPreferences.hideChannelPrefix,
            showCategoryFlags = displayPreferences.showCategoryFlags,
            hideCategoryPrefix = displayPreferences.hideLiveCategoryPrefix,
            catalogLoadError = initialRefreshError,
            catalogLoading = initialRefreshInProgress,
            modifier = modifier,
        )
    }
}

private enum class LiveBrowsePage {
    HOME,
    CATEGORY,
    SEARCH,
    FAVORITES,
    PLAYER,
    CATCH_UP,
}

private data class LiveRecordingDialogRequest(
    val channelId: String,
    val channelName: String,
    val program: LiveProgram?,
)

@Composable
private fun LiveSourceScreen(
    source: SourceSummary,
    repository: LiveOrganizationRepository,
    guideRepository: LiveGuideRepository,
    catchUpRepository: LiveCatchUpRepository,
    liveRecordingRepository: app.ownplay.mobile.feature.live.domain.LiveRecordingRepository,
    liveRecordingScheduler: app.ownplay.mobile.feature.live.data.AndroidLiveRecordingScheduler,
    downloadRepository: DownloadRepository,
    playbackSessionController: PlaybackSessionController,
    playbackPreferencesRepository: PlaybackPreferencesRepository,
    playbackEngine: Media3PlaybackEngine,
    artworkLoader: LibraryArtworkLoader,
    compactMediaRows: Boolean,
    showChannelLogos: Boolean,
    preferTvgName: Boolean,
    hideChannelPrefix: Boolean,
    showCategoryFlags: Boolean,
    hideCategoryPrefix: Boolean,
    catalogLoadError: String?,
    catalogLoading: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val sourceId = source.sourceId
    val providerFlow = remember(repository, sourceId) { repository.observeProviderCatalog(sourceId) }
    val favoritesFlow = remember(repository, sourceId) { repository.observeFavoriteChannelIds(sourceId) }
    val providerCatalog by providerFlow.collectAsState(
        initial = ProviderLiveCatalogSnapshot(categories = emptyList(), channels = emptyList()),
    )
    val favoriteChannelIds by favoritesFlow.collectAsState(initial = emptySet())
    val recordingRows by liveRecordingRepository.recordings.collectAsState(initial = emptyList())
    val playbackState by playbackSessionController.state.collectAsState()
    val playbackPreferences by playbackPreferencesRepository.preferences.collectAsState(
        initial = PlaybackPreferences(),
    )
    val scope = rememberCoroutineScope()

    var pageName by rememberSaveable(sourceId.value) { mutableStateOf(LiveBrowsePage.HOME.name) }
    var selectedProviderCategoryId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable(sourceId.value) { mutableStateOf("") }
    var searchReturnPageName by rememberSaveable(sourceId.value) {
        mutableStateOf(LiveBrowsePage.HOME.name)
    }
    var searchReturnCategoryId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }
    var playerReturnPageName by rememberSaveable(sourceId.value) {
        mutableStateOf(LiveBrowsePage.HOME.name)
    }
    var catchUpReturnPageName by rememberSaveable(sourceId.value) {
        mutableStateOf(LiveBrowsePage.HOME.name)
    }
    var playerReturnCategoryId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }
    var homeEntryId by rememberSaveable(sourceId.value) { mutableStateOf(0L) }
    var categoryEntryId by rememberSaveable(sourceId.value) { mutableStateOf(0L) }
    var favoritesEntryId by rememberSaveable(sourceId.value) { mutableStateOf(0L) }
    var searchEntryId by rememberSaveable(sourceId.value) { mutableStateOf(0L) }
    var catchUpEntryId by rememberSaveable(sourceId.value) { mutableStateOf(0L) }
    val categoryListState = rememberContextLazyListState(sourceId.value, homeEntryId, "categories")
    val channelListState = rememberContextLazyListState(sourceId.value, categoryEntryId, "channels", selectedProviderCategoryId)
    val favoritesListState = rememberContextLazyListState(sourceId.value, favoritesEntryId, "favorites")
    val searchListState = rememberContextLazyListState(sourceId.value, searchEntryId, "search", searchQuery)
    val catchUpListState = rememberContextLazyListState(sourceId.value, catchUpEntryId, "catch-up")
    var catchUpRefreshRevision by rememberSaveable(sourceId.value) { mutableStateOf(0) }
    var catchUpChannelIds by remember(sourceId.value) { mutableStateOf<Set<String>>(emptySet()) }
    var catchUpChannelsLoading by remember(sourceId.value) { mutableStateOf(false) }
    var selectedCatchUpChannelId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }
    var pendingResumeProgram by remember { mutableStateOf<LiveCatchUpProgram?>(null) }
    var recordingDialogRequest by remember { mutableStateOf<LiveRecordingDialogRequest?>(null) }
    var selectedEpgProgram by remember { mutableStateOf<LiveProgram?>(null) }
    var recordingCatchUpProgram by remember { mutableStateOf<LiveCatchUpProgram?>(null) }
    var recordingCatchUpFormatSupported by remember { mutableStateOf(false) }
    var recordingCatchUpChecking by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var pendingNotificationPermissionRequest by remember {
        mutableStateOf<LiveRecordingDialogRequest?>(null)
    }
    var notificationPermissionSettingsVisible by remember { mutableStateOf(false) }
    var fullscreenAnchorIndex by rememberSaveable(sourceId.value) { mutableStateOf<Int?>(null) }
    var rememberedPlayerChannelId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }

    val recordingNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingNotificationPermissionRequest
        if (granted && pending != null) {
            pendingNotificationPermissionRequest = null
            notificationPermissionSettingsVisible = false
            recordingDialogRequest = pending
        } else if (pending != null) {
            recordingDialogRequest = null
            notificationPermissionSettingsVisible = true
            operationMessage = "No recording was started. Allow notifications, then try again."
        }
    }

    fun hasRecordingNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    val page = LiveBrowsePage.entries.firstOrNull { it.name == pageName } ?: LiveBrowsePage.HOME
    val providerOptions = remember(providerCatalog.categories, providerCatalog.channels) {
        LiveBrowseStatePolicy.providerCategoryOptions(providerCatalog)
    }
    val selectedCategory = providerOptions.firstOrNull { it.categoryId == selectedProviderCategoryId }
    val channelById = remember(providerCatalog.channels) {
        providerCatalog.channels.associateBy(LiveOrganizationChannel::channelId)
    }
    val categoryLabelById = remember(providerOptions, showCategoryFlags, hideCategoryPrefix) {
        providerOptions.associate { category ->
            category.categoryId to ProviderCategoryDisplayPolicy.label(
                rawName = category.displayName,
                hideRegionPrefix = hideCategoryPrefix,
                showFlag = showCategoryFlags,
            )
        }
    }
    val categoryLabelByChannelId = remember(providerCatalog.channels, categoryLabelById) {
        providerCatalog.channels.associate { channel ->
            channel.channelId to channel.providerCategoryId?.let(categoryLabelById::get)
        }
    }
    val providerChannelCountByCategoryId = remember(providerCatalog.channels) {
        buildMap {
            providerCatalog.channels.forEach { channel ->
                val categoryId = channel.providerCategoryId
                    ?: LiveBrowseStatePolicy.PROVIDER_UNCATEGORIZED_ID
                put(categoryId, (get(categoryId) ?: 0) + 1)
            }
        }
    }
    val categoryChannelIds = remember(providerCatalog, selectedProviderCategoryId) {
        LiveBrowseStatePolicy.visibleProviderChannelIds(
            catalog = providerCatalog,
            categoryId = selectedProviderCategoryId,
            favoritesOnly = false,
            favoriteChannelIds = emptySet(),
        )
    }
    val favoriteChannelIdsOrdered = remember(providerCatalog.channels, favoriteChannelIds) {
        providerCatalog.channels
            .asSequence()
            .filter { it.channelId in favoriteChannelIds }
            .map(LiveOrganizationChannel::channelId)
            .toList()
    }
    val allProviderChannelIds = remember(providerCatalog.channels) {
        providerCatalog.channels.map(LiveOrganizationChannel::channelId)
    }
    val searchResultIds = remember(providerCatalog.channels, allProviderChannelIds, searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            LiveBrowseStatePolicy.searchChannelIds(
                channels = providerCatalog.channels,
                query = searchQuery,
                favoritesOnly = false,
                favoriteChannelIds = emptySet(),
                candidateChannelIds = allProviderChannelIds,
            )
        }
    }

    val playbackTarget = (playbackState.target as? PlaybackTarget.LiveChannel)
        ?.takeIf { it.sourceId == sourceId }
    val catchUpPlaybackTarget = (playbackState.target as? PlaybackTarget.CatchUp)
        ?.takeIf { it.sourceId == sourceId }
    val activePlaybackChannelId = playbackTarget?.channelId ?: catchUpPlaybackTarget?.channelId
    val playbackChannelName = activePlaybackChannelId?.let { channelId ->
        channelById[channelId]
            ?.let { channel ->
                LiveChannelDisplayPolicy.displayName(
                    channel = channel,
                    preferTvgName = preferTvgName,
                    hideChannelPrefix = hideChannelPrefix,
                    showCountryFlag = false,
                )
            }
            ?: "Live channel"
    }
    val fullGuide = rememberLiveSchedule(guideRepository, sourceId, playbackTarget?.channelId)
    val catchUpRequestChannelId = if (page == LiveBrowsePage.CATCH_UP) {
        selectedCatchUpChannelId
    } else {
        activePlaybackChannelId
    }
    val catchUpCatalog = rememberLiveCatchUpCatalog(
        repository = catchUpRepository,
        sourceId = sourceId,
        channelId = catchUpRequestChannelId,
        refreshRevision = catchUpRefreshRevision,
    )
    val epgNowEpochSeconds = rememberEpgClock()

    LaunchedEffect(recordingDialogRequest, catchUpRepository, sourceId) {
        val request = recordingDialogRequest
        val program = request?.program
        val end = program?.endEpochSeconds
        if (request == null || end == null || end > epgNowEpochSeconds) {
            recordingCatchUpProgram = null
            recordingCatchUpFormatSupported = false
            recordingCatchUpChecking = false
        } else {
            recordingCatchUpProgram = null
            recordingCatchUpFormatSupported = false
            recordingCatchUpChecking = true
            try {
                val catalog = catchUpRepository.loadCatalog(sourceId, request.channelId)
                val archived = catalog.programs.firstOrNull { candidate ->
                    candidate.startEpochSeconds == program.startEpochSeconds &&
                        candidate.endEpochSeconds == program.endEpochSeconds
                }
                recordingCatchUpProgram = archived
                recordingCatchUpFormatSupported =
                    archived?.programId?.let { it in catalog.downloadableProgramIds } == true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordingCatchUpProgram = null
            } finally {
                recordingCatchUpChecking = false
            }
        }
    }

    LaunchedEffect(page, sourceId, providerCatalog.channels.size) {
        if (page == LiveBrowsePage.CATCH_UP) {
            catchUpChannelsLoading = true
            try {
                catchUpChannelIds = catchUpRepository.loadAvailableChannelIds(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                catchUpChannelIds = emptySet()
                operationMessage = "Catch-up channels could not be loaded."
            } finally {
                catchUpChannelsLoading = false
            }
        }
    }

    val playerContextChannelIds = remember(
        playerReturnPageName,
        playerReturnCategoryId,
        categoryChannelIds,
        favoriteChannelIdsOrdered,
        searchResultIds,
    ) {
        when (LiveBrowsePage.entries.firstOrNull { it.name == playerReturnPageName }) {
            LiveBrowsePage.CATEGORY -> {
                if (playerReturnCategoryId == selectedProviderCategoryId) {
                    categoryChannelIds
                } else {
                    LiveBrowseStatePolicy.visibleProviderChannelIds(
                        catalog = providerCatalog,
                        categoryId = playerReturnCategoryId,
                        favoritesOnly = false,
                        favoriteChannelIds = emptySet(),
                    )
                }
            }
            LiveBrowsePage.FAVORITES -> favoriteChannelIdsOrdered
            LiveBrowsePage.SEARCH -> searchResultIds
            else -> listOfNotNull(playbackTarget?.channelId ?: rememberedPlayerChannelId)
        }
    }

    LaunchedEffect(playbackTarget?.channelId, playerContextChannelIds) {
        val currentIndex = playbackTarget?.channelId?.let(playerContextChannelIds::indexOf) ?: -1
        if (currentIndex >= 0) fullscreenAnchorIndex = currentIndex
    }

    LaunchedEffect(playbackTarget?.channelId) {
        playbackTarget?.channelId?.let { rememberedPlayerChannelId = it }
    }

    LaunchedEffect(
        page,
        rememberedPlayerChannelId,
        playbackTarget?.channelId,
        catchUpPlaybackTarget?.programId,
        providerCatalog.channels.size,
    ) {
        if (
            page == LiveBrowsePage.PLAYER &&
            playbackTarget == null &&
            catchUpPlaybackTarget == null
        ) {
            val channelId = rememberedPlayerChannelId
            if (channelId != null && channelById.containsKey(channelId)) {
                playbackSessionController.activateLiveChannel(
                    PlaybackTarget.LiveChannel(sourceId = sourceId, channelId = channelId),
                )
            } else if (channelId != null && providerCatalog.channels.isNotEmpty()) {
                rememberedPlayerChannelId = null
                selectedProviderCategoryId = playerReturnCategoryId
                pageName = playerReturnPageName
            }
        }
    }

    fun openSearch(returnPage: LiveBrowsePage) {
        searchReturnPageName = returnPage.name
        searchReturnCategoryId = selectedProviderCategoryId
        searchEntryId += 1L; pageName = LiveBrowsePage.SEARCH.name
    }

    fun leaveSearch() {
        selectedProviderCategoryId = searchReturnCategoryId
        pageName = searchReturnPageName
    }

    fun openPlayer(channelId: String, returnPage: LiveBrowsePage) {
        playerReturnPageName = returnPage.name
        playerReturnCategoryId = selectedProviderCategoryId
        rememberedPlayerChannelId = channelId
        pageName = LiveBrowsePage.PLAYER.name
        scope.launch {
            playbackSessionController.activateLiveChannel(
                PlaybackTarget.LiveChannel(sourceId = sourceId, channelId = channelId),
            )
        }
    }

    fun leavePlayer() {
        rememberedPlayerChannelId = null
        playbackSessionController.clear()
        selectedProviderCategoryId = playerReturnCategoryId
        pageName = playerReturnPageName
    }

    fun startCatchUp(program: LiveCatchUpProgram, resumePositionMs: Long?) {
        val channelId = selectedCatchUpChannelId ?: activePlaybackChannelId ?: return
        scope.launch {
            playbackSessionController.activateCatchUp(
                target = PlaybackTarget.CatchUp(
                    sourceId = sourceId,
                    channelId = channelId,
                    programId = program.programId,
                    title = program.title,
                    startEpochSeconds = program.startEpochSeconds,
                    endEpochSeconds = program.endEpochSeconds,
                ),
                resumePositionMs = resumePositionMs,
            )
        }
    }

    fun requestRecording(channelId: String, channelName: String, program: LiveProgram?) {
        recordingDialogRequest = LiveRecordingDialogRequest(channelId, channelName, program)
    }

    fun recordProgram(program: LiveProgram) {
        val channelId = playbackTarget?.channelId ?: return
        requestRecording(channelId, playbackChannelName ?: "Live channel", program)
    }

    fun beginRecording(
        request: LiveRecordingDialogRequest,
        requestedStart: Long,
        requestedEnd: Long,
        stopPolicy: LiveRecordingStopPolicy,
    ) {
        if (!hasRecordingNotificationPermission()) {
            pendingNotificationPermissionRequest = request
            recordingDialogRequest = null
            recordingNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val now = System.currentTimeMillis() / 1_000L
        if (requestedEnd <= now) {
            operationMessage = "The program window has already ended."
            recordingDialogRequest = null
            return
        }
        val startsNow = requestedStart <= now
        val start = if (startsNow) now else requestedStart
        val end = app.ownplay.mobile.feature.live.domain.RecordingClockDeadlinePolicy
            .confirmedEnd(requestedStart, requestedEnd, now, stopPolicy) ?: return
        val recording = LiveRecording(
            recordingId = LiveRecordingPolicy.recordingId(sourceId.value, request.channelId, start),
            sourceId = sourceId.value,
            channelId = request.channelId,
            channelName = request.channelName,
            title = request.program?.title?.takeIf(String::isNotBlank) ?: request.channelName,
            startEpochSeconds = start,
            endEpochSeconds = end,
            status = app.ownplay.mobile.feature.live.domain.LiveRecordingStatus.SCHEDULED,
            stopPolicy = stopPolicy,
            deadlineEpochSeconds = end,
        )
        if (startsNow) {
            val started = liveRecordingScheduler.startNow(recording)
            operationMessage = if (started) {
                "Recording started. See Downloads > Recordings."
            } else {
                liveRecordingRepository.get(recording.recordingId)?.safeError
                    ?: "Android could not start this recording."
            }
            return
        }
        when (liveRecordingScheduler.schedule(recording)) {
            LiveRecordingScheduleResult.SCHEDULED ->
                operationMessage = "Recording scheduled."
            LiveRecordingScheduleResult.ALREADY_EXISTS ->
                operationMessage = "This recording is already scheduled or has already been saved."
            LiveRecordingScheduleResult.EXACT_ALARM_ACCESS_REQUIRED -> {
                operationMessage = "Enable exact alarms to schedule recordings."
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + context.packageName)),
                        )
                    }
                }
            }
            LiveRecordingScheduleResult.SOURCE_UNAVAILABLE ->
                operationMessage = "This source is being removed or is unavailable."
            LiveRecordingScheduleResult.FAILED ->
                operationMessage = "The recording could not be scheduled."
        }
        recordingDialogRequest = null
    }

    fun leaveCatchUpPlaybackToPlayer() {
        val target = catchUpPlaybackTarget ?: return
        scope.launch {
            rememberedPlayerChannelId = target.channelId
            playbackSessionController.activateLiveChannel(
                PlaybackTarget.LiveChannel(sourceId, target.channelId),
            )
            catchUpRefreshRevision += 1
            pageName = LiveBrowsePage.PLAYER.name
        }
    }

    BackHandler(
        enabled = catchUpPlaybackTarget != null &&
            playbackState.presentation == PlaybackPresentation.PREVIEW,
    ) {
        leaveCatchUpPlaybackToPlayer()
    }

    BackHandler(
        enabled = catchUpPlaybackTarget == null &&
            page != LiveBrowsePage.HOME &&
            playbackState.presentation != PlaybackPresentation.FULLSCREEN,
    ) {
        when (page) {
            LiveBrowsePage.PLAYER -> leavePlayer()
            LiveBrowsePage.CATCH_UP -> pageName = catchUpReturnPageName
            LiveBrowsePage.SEARCH -> leaveSearch()
            LiveBrowsePage.CATEGORY,
            LiveBrowsePage.FAVORITES,
            -> { homeEntryId += 1L; pageName = LiveBrowsePage.HOME.name }
            LiveBrowsePage.HOME -> Unit
        }
    }

    LaunchedEffect(catchUpPlaybackTarget?.programId, playbackState.endedNaturally) {
        val target = catchUpPlaybackTarget
        if (target != null && playbackState.endedNaturally) {
            rememberedPlayerChannelId = target.channelId
            playbackSessionController.activateLiveChannel(
                PlaybackTarget.LiveChannel(sourceId, target.channelId),
            )
            catchUpRefreshRevision += 1
            pageName = LiveBrowsePage.PLAYER.name
        }
    }

    LaunchedEffect(catchUpPlaybackTarget?.programId) {
        val programId = catchUpPlaybackTarget?.programId ?: return@LaunchedEffect
        while (true) {
            delay(5_000L)
            if ((playbackSessionController.state.value.target as? PlaybackTarget.CatchUp)?.programId != programId) {
                break
            }
            playbackSessionController.checkpointProgress()
        }
    }

    fun toggleFavorite(channelId: String) {
        scope.launch {
            if (!repository.setFavorite(
                    sourceId = sourceId,
                    channelId = channelId,
                    favorite = channelId !in favoriteChannelIds,
                )
            ) {
                operationMessage = "Favorite could not be updated."
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (page) {
            LiveBrowsePage.HOME -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Live",
                            color = OwnPlayColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            source.displayName,
                            color = OwnPlayColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { openSearch(LiveBrowsePage.HOME) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Search")
                        }
                        TextButton(
                            onClick = {
                                selectedCatchUpChannelId = null
                                catchUpReturnPageName = LiveBrowsePage.HOME.name
                                operationMessage = null
                                catchUpEntryId += 1L; pageName = LiveBrowsePage.CATCH_UP.name
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Catch-up")
                        }
                        TextButton(
                            onClick = { favoritesEntryId += 1L; pageName = LiveBrowsePage.FAVORITES.name },
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Favorites" },
                        ) {
                            Text("★")
                        }
                    }
                }
                if (catalogLoading) {
                    Text("Importing Live catalog…", color = OwnPlayColors.TextMuted)
                }
                catalogLoadError?.let { Text(it, color = OwnPlayColors.Error) }
                operationMessage?.let { Text(it, color = OwnPlayColors.Error) }

                if (providerOptions.isEmpty() && !catalogLoading) {
                    Text("No Live categories are available.", color = OwnPlayColors.TextMuted)
                } else {
                    LazyColumn(
                        state = categoryListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(providerOptions, key = { it.categoryId }) { category ->
                            val count = providerChannelCountByCategoryId[category.categoryId] ?: 0
                            Surface(
                                color = OwnPlayColors.Surface,
                                shape = OwnPlayShapes.Small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        selectedProviderCategoryId = category.categoryId
                                        scope.launch { channelListState.scrollToItem(0) }
                                        categoryEntryId += 1L; pageName = LiveBrowsePage.CATEGORY.name
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        categoryLabelById[category.categoryId] ?: category.displayName,
                                        color = OwnPlayColors.TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = count.toString(),
                                            color = OwnPlayColors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text("›", color = OwnPlayColors.TextMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LiveBrowsePage.CATEGORY -> {
                OwnPlayMobileTopBar(
                    title = selectedCategory
                        ?.categoryId
                        ?.let(categoryLabelById::get)
                        ?: "Live",
                    subtitle = categoryChannelIds.size.toString() + " channels",
                    onBack = { homeEntryId += 1L; pageName = LiveBrowsePage.HOME.name },
                    actions = {
                        TextButton(
                            onClick = { openSearch(LiveBrowsePage.CATEGORY) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Search")
                        }
                    },
                )
                if (categoryChannelIds.isEmpty() && !catalogLoading) {
                    Text("No channels in this provider category.", color = OwnPlayColors.TextMuted)
                } else {
                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compactMediaRows) 3.dp else 6.dp),
                    ) {
                        items(categoryChannelIds, key = { it }) { channelId ->
                            val channel = channelById[channelId] ?: return@items
                            val guide = rememberLiveGuide(guideRepository, sourceId, channel.channelId)
                            LiveChannelRow(
                                channel = channel,
                                guide = guide,
                                nowEpochSeconds = epgNowEpochSeconds,
                                selected = false,
                                favorite = channel.channelId in favoriteChannelIds,
                                compact = compactMediaRows,
                                showLogo = showChannelLogos,
                                preferTvgName = preferTvgName,
                                hideChannelPrefix = hideChannelPrefix,
                                artworkLoader = artworkLoader,
                                onActivate = { openPlayer(channel.channelId, LiveBrowsePage.CATEGORY) },
                                onFavorite = { toggleFavorite(channel.channelId) },
                            )
                        }
                    }
                }
            }

            LiveBrowsePage.SEARCH -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::leaveSearch) { Text("Back") }
                    Text(
                        "Search Live",
                        color = OwnPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        if (searchQuery != it) {
                            searchQuery = it
                            scope.launch { searchListState.scrollToItem(0) }
                        }
                    },
                    label = { Text("Search channels") },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            TextButton(onClick = {
                                searchQuery = ""
                                scope.launch { searchListState.scrollToItem(0) }
                            }) {
                                Text("Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searchQuery.isBlank()) {
                    Text("Search all available channels from this source.", color = OwnPlayColors.TextMuted)
                } else if (searchResultIds.isEmpty()) {
                    Text("No channels match this search.", color = OwnPlayColors.TextMuted)
                } else {
                    LazyColumn(
                        state = searchListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compactMediaRows) 3.dp else 6.dp),
                    ) {
                        items(searchResultIds, key = { it }) { channelId ->
                            val channel = channelById[channelId] ?: return@items
                            val guide = rememberLiveGuide(guideRepository, sourceId, channel.channelId)
                            LiveChannelRow(
                                channel = channel,
                                guide = guide,
                                nowEpochSeconds = epgNowEpochSeconds,
                                selected = false,
                                favorite = channel.channelId in favoriteChannelIds,
                                compact = compactMediaRows,
                                showLogo = showChannelLogos,
                                preferTvgName = preferTvgName,
                                hideChannelPrefix = hideChannelPrefix,
                                contextLabel = categoryLabelByChannelId[channel.channelId],
                                artworkLoader = artworkLoader,
                                onActivate = { openPlayer(channel.channelId, LiveBrowsePage.SEARCH) },
                                onFavorite = { toggleFavorite(channel.channelId) },
                            )
                        }
                    }
                }
            }

            LiveBrowsePage.FAVORITES -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { homeEntryId += 1L; pageName = LiveBrowsePage.HOME.name }) { Text("Back") }
                        Text(
                            "Favorites",
                            color = OwnPlayColors.TextPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(onClick = { openSearch(LiveBrowsePage.FAVORITES) }) { Text("Search") }
                }
                if (favoriteChannelIdsOrdered.isEmpty()) {
                    Text(
                        "No favorite channels yet. Tap ☆ on a channel to save it here.",
                        color = OwnPlayColors.TextMuted,
                    )
                } else {
                    LazyColumn(
                        state = favoritesListState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(if (compactMediaRows) 3.dp else 6.dp),
                    ) {
                        items(favoriteChannelIdsOrdered, key = { it }) { channelId ->
                            val channel = channelById[channelId] ?: return@items
                            val guide = rememberLiveGuide(guideRepository, sourceId, channel.channelId)
                            LiveChannelRow(
                                channel = channel,
                                guide = guide,
                                nowEpochSeconds = epgNowEpochSeconds,
                                selected = false,
                                favorite = true,
                                compact = compactMediaRows,
                                showLogo = showChannelLogos,
                                preferTvgName = preferTvgName,
                                hideChannelPrefix = hideChannelPrefix,
                                contextLabel = categoryLabelByChannelId[channel.channelId],
                                artworkLoader = artworkLoader,
                                onActivate = { openPlayer(channel.channelId, LiveBrowsePage.FAVORITES) },
                                onFavorite = { toggleFavorite(channel.channelId) },
                            )
                        }
                    }
                }
            }

            LiveBrowsePage.PLAYER -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::leavePlayer) { Text("Back") }
                    val channelId = playbackTarget?.channelId
                    val playerFavorite =
                        channelId != null && channelId in favoriteChannelIds
                    TextButton(
                        enabled = channelId != null,
                        onClick = { channelId?.let(::toggleFavorite) },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (playerFavorite) "Remove favorite" else "Add favorite"
                        },
                    ) {
                        Text(if (playerFavorite) "★" else "☆")
                    }
                }
                if (
                    playbackTarget != null &&
                    playbackChannelName != null &&
                    playbackState.presentation == PlaybackPresentation.PREVIEW
                ) {
                    PlaybackPreviewCard(
                        channelName = playbackChannelName,
                        readiness = playbackState.readiness,
                        playbackEngine = playbackEngine,
                        catchUpSupported = catchUpCatalog.supported,
                        onFullscreen = {
                            val entered = (context.findLiveActivity() as? MainActivity)
                                ?.requestManualLiveFullscreen()
                                ?: false
                            if (!entered) playbackSessionController.enterFullscreen()
                        },
                        onRetry = {
                            scope.launch { playbackSessionController.retryActiveTarget() }
                        },
                        onCatchUp = {
                            catchUpRefreshRevision += 1
                            selectedCatchUpChannelId = playbackTarget.channelId
                            catchUpReturnPageName = LiveBrowsePage.PLAYER.name
                            operationMessage = null
                            catchUpEntryId += 1L
                            pageName = LiveBrowsePage.CATCH_UP.name
                        },
                    )
                    LiveFullEpg(
                        sourceId = sourceId,
                        programs = fullGuide,
                        channelId = playbackTarget.channelId,
                        nowEpochSeconds = epgNowEpochSeconds,
                        recordings = recordingRows,
                        onSelectProgram = { selectedEpgProgram = it },
                        onRecordChannel = {
                            requestRecording(playbackTarget.channelId, playbackChannelName, null)
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else if (playbackTarget == null && catchUpPlaybackTarget == null) {
                    Text("Starting channel…", color = OwnPlayColors.TextMuted)
                }
                operationMessage?.let { Text(it, color = OwnPlayColors.Error) }
            }

            LiveBrowsePage.CATCH_UP -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (catchUpPlaybackTarget != null) {
                                leaveCatchUpPlaybackToPlayer()
                            } else if (selectedCatchUpChannelId != null) {
                                selectedCatchUpChannelId = null
                            } else {
                                pageName = catchUpReturnPageName
                            }
                        },
                    ) { Text(if (selectedCatchUpChannelId != null) "Channels" else "Back") }
                    Text("Catch-up", color = OwnPlayColors.TextPrimary, fontWeight = FontWeight.Bold)
                }
                operationMessage?.let { Text(it, color = OwnPlayColors.Error) }
                if (
                    catchUpPlaybackTarget != null &&
                    playbackState.presentation == PlaybackPresentation.PREVIEW
                ) {
                    CatchUpPreviewCard(
                        target = catchUpPlaybackTarget,
                        readiness = playbackState.readiness,
                        playbackEngine = playbackEngine,
                        onFullscreen = playbackSessionController::enterFullscreen,
                        onRetry = {
                            scope.launch { playbackSessionController.retryActiveTarget() }
                        },
                    )
                }
                if (selectedCatchUpChannelId == null) {
                    when {
                        catchUpChannelsLoading ->
                            Text("Checking channel archives…", color = OwnPlayColors.TextMuted)
                        catchUpChannelIds.isEmpty() ->
                            Text(
                                "No channels with an available archive were found for this source.",
                                color = OwnPlayColors.TextMuted,
                            )
                        else -> LazyColumn(
                            state = catchUpListState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                catchUpChannelIds.toList().sortedBy { channelById[it]?.providerOrder ?: Int.MAX_VALUE },
                                key = { it },
                            ) { channelId ->
                                val channel = channelById[channelId] ?: return@items
                                Surface(
                                    color = OwnPlayColors.SurfaceRaised,
                                    shape = OwnPlayShapes.Medium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clickable { selectedCatchUpChannelId = channelId },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            LiveChannelDisplayPolicy.displayName(
                                                channel,
                                                preferTvgName,
                                                hideChannelPrefix,
                                                false,
                                            ),
                                            color = OwnPlayColors.TextPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("›", color = OwnPlayColors.TextMuted)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        channelById[selectedCatchUpChannelId]?.name ?: "Archived programs",
                        color = OwnPlayColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LiveCatchUpList(
                        catalog = catchUpCatalog,
                        listState = catchUpListState,
                        onProgramSelected = { program ->
                            val resumable = LiveCatchUpPolicy.resumablePositionMs(program)
                            if (resumable != null) {
                                pendingResumeProgram = program
                            } else {
                                startCatchUp(program, null)
                            }
                        },
                        onBackToLive = { selectedCatchUpChannelId = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (
        playbackTarget != null &&
        playbackChannelName != null &&
        playbackState.presentation == PlaybackPresentation.FULLSCREEN
    ) {
        LiveFullscreenPresentation(
            channelName = playbackChannelName,
            playbackState = playbackState,
            playbackSessionController = playbackSessionController,
            playbackEngine = playbackEngine,
            orderedChannelIds = playerContextChannelIds,
            currentChannelId = playbackTarget.channelId,
            lastKnownIndex = fullscreenAnchorIndex,
            channelNameForId = { channelId ->
                channelById[channelId]?.let { channel ->
                    LiveChannelDisplayPolicy.displayName(
                        channel = channel,
                        preferTvgName = preferTvgName,
                        hideChannelPrefix = hideChannelPrefix,
                        showCountryFlag = false,
                    )
                } ?: "Live channel"
            },
            playerVolume = playbackPreferences.playerVolume,
            onPlayerVolumeCommitted = { volume ->
                playbackSessionController.setPlayerVolume(volume)
                scope.launch { playbackPreferencesRepository.setPlayerVolume(volume) }
            },
            onSwitchChannel = { channelId ->
                rememberedPlayerChannelId = channelId
                scope.launch {
                    playbackSessionController.activateLiveChannel(
                        PlaybackTarget.LiveChannel(sourceId, channelId),
                    )
                    playbackSessionController.enterFullscreen()
                }
            },
            onRetry = {
                scope.launch { playbackSessionController.retryActiveTarget() }
            },
            catchUpEnabled = catchUpCatalog.supported,
            onCatchUp = {
                catchUpRefreshRevision += 1
                selectedCatchUpChannelId = playbackTarget.channelId
                catchUpReturnPageName = LiveBrowsePage.PLAYER.name
                operationMessage = null
                catchUpEntryId += 1L; pageName = LiveBrowsePage.CATCH_UP.name
                val exited = (context.findLiveActivity() as? MainActivity)
                    ?.exitLiveFullscreen()
                    ?: false
                if (!exited) playbackSessionController.returnToPreview()
            },
            onPictureInPicture = {
                (context.findLiveActivity() as? MainActivity)
                    ?.requestOwnPlayPictureInPicture()
            },
            onDismiss = {
                val exited = (context.findLiveActivity() as? MainActivity)
                    ?.exitLiveFullscreen()
                    ?: false
                if (!exited) playbackSessionController.returnToPreview()
            },
        )
    }

    val resumeProgram = pendingResumeProgram
    if (resumeProgram != null) {
        CatchUpResumeSheet(
            program = resumeProgram,
            onResume = {
                pendingResumeProgram = null
                startCatchUp(resumeProgram, LiveCatchUpPolicy.resumablePositionMs(resumeProgram))
            },
            onStartOver = {
                pendingResumeProgram = null
                startCatchUp(resumeProgram, null)
            },
            onDismiss = { pendingResumeProgram = null },
        )
    }

    if (
        catchUpPlaybackTarget != null &&
        playbackState.presentation == PlaybackPresentation.FULLSCREEN
    ) {
        CatchUpFullscreenPresentation(
            target = catchUpPlaybackTarget,
            playbackState = playbackState,
            playbackSessionController = playbackSessionController,
            playbackEngine = playbackEngine,
            onPictureInPicture = {
                (context.findLiveActivity() as? MainActivity)
                    ?.requestOwnPlayPictureInPicture()
            },
            onRetry = {
                scope.launch { playbackSessionController.retryActiveTarget() }
            },
            onDismiss = playbackSessionController::returnToPreview,
        )
    }

    selectedEpgProgram?.let { program ->
        LiveEpgProgramDetailsDialog(
            program = program,
            nowEpochSeconds = epgNowEpochSeconds,
            onAction = { selectedEpgProgram = null; recordProgram(it) },
            onDismiss = { selectedEpgProgram = null },
        )
    }

    recordingDialogRequest?.let { request ->
        LiveRecordingOptionsDialog(
            request = request,
            nowEpochSeconds = epgNowEpochSeconds,
            catchUpAvailable = recordingCatchUpProgram != null,
            catchUpFormatSupported = recordingCatchUpFormatSupported,
            catchUpChecking = recordingCatchUpChecking,
            onDismiss = { recordingDialogRequest = null },
            onRecord = { start, end, policy -> beginRecording(request, start, end, policy) },
            onDownloadCatchUp = {
                val archived = recordingCatchUpProgram
                if (archived == null) {
                    operationMessage = "This program is not available in the source archive."
                } else {
                    scope.launch {
                        try {
                            val identity = CatchUpDownloadIdentity(
                                channelId = request.channelId,
                                programId = archived.programId,
                                startEpochSeconds = archived.startEpochSeconds,
                                endEpochSeconds = archived.endEpochSeconds,
                            )
                            val item = downloadRepository.enqueue(
                                DownloadRequest(
                                    sourceId = sourceId,
                                    mediaKind = DownloadMediaKind.CATCH_UP,
                                    contentId = archived.programId,
                                    title = archived.title,
                                    sourceContentIdentity = identity.encode(),
                                ),
                            )
                            operationMessage = when (item.status) {
                                app.ownplay.mobile.downloads.domain.DownloadStatus.QUEUED,
                                app.ownplay.mobile.downloads.domain.DownloadStatus.WAITING_FOR_WIFI,
                                app.ownplay.mobile.downloads.domain.DownloadStatus.DOWNLOADING,
                                app.ownplay.mobile.downloads.domain.DownloadStatus.COMPLETED,
                                -> "Catch-up download added to Downloads."
                                else -> "This catch-up format could not be queued."
                            }
                            recordingDialogRequest = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            operationMessage = "The catch-up download could not be queued."
                        }
                    }
                }
            },
        )
    }

    if (notificationPermissionSettingsVisible) {
        val pending = pendingNotificationPermissionRequest
        AlertDialog(
            onDismissRequest = {
                notificationPermissionSettingsVisible = false
                pendingNotificationPermissionRequest = null
            },
            title = { Text("Notifications are required for recording") },
            text = {
                Text("OwnPlay uses notifications to show recording progress and completion. No recording or schedule will start until notifications are allowed.")
            },
            confirmButton = {
                TextButton(
                    enabled = pending != null,
                    onClick = {
                        if (pending != null && hasRecordingNotificationPermission()) {
                            recordingDialogRequest = pending
                            pendingNotificationPermissionRequest = null
                            notificationPermissionSettingsVisible = false
                        } else {
                            recordingNotificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    },
                ) { Text("Try again") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            notificationPermissionSettingsVisible = false
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:" + context.packageName)),
                                )
                            }
                        },
                    ) { Text("Open settings") }
                    TextButton(
                        onClick = {
                            notificationPermissionSettingsVisible = false
                            pendingNotificationPermissionRequest = null
                        },
                    ) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun LiveChannelRow(
    channel: LiveOrganizationChannel,
    guide: LiveNowNext,
    nowEpochSeconds: Long,
    selected: Boolean,
    favorite: Boolean,
    compact: Boolean,
    showLogo: Boolean,
    preferTvgName: Boolean,
    hideChannelPrefix: Boolean,
    contextLabel: String? = null,
    artworkLoader: LibraryArtworkLoader,
    onActivate: () -> Unit,
    onFavorite: () -> Unit,
) {
    Surface(
        color = OwnPlayColors.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLogo && !channel.logoUrl.isNullOrBlank()) {
                LibraryArtwork(
                    url = channel.logoUrl,
                    loader = artworkLoader,
                    compact = compact,
                    presentation = ArtworkPresentation.CHANNEL_LOGO,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = LiveChannelDisplayPolicy.displayName(
                        channel,
                        preferTvgName,
                        hideChannelPrefix,
                        showCountryFlag = false,
                    ),
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                contextLabel?.takeIf(String::isNotBlank)?.let { label ->
                    Text(
                        text = label,
                        color = OwnPlayColors.TextMuted,
                        maxLines = 1,
                    )
                }
                guide.now?.let { current ->
                    val progress = LiveGuidePolicy.progressFraction(current, nowEpochSeconds)
                    Text(
                        text = current.title,
                        color = OwnPlayColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentProgramLine(current, nowEpochSeconds),
                        color = OwnPlayColors.TextMuted,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (selected) {
                    guide.next?.let { next ->
                        Text(
                            text = "Next ${programTimeRange(next)} • ${next.title}",
                            color = OwnPlayColors.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = if (favorite) "★" else "☆",
                color = if (favorite) OwnPlayColors.Accent else OwnPlayColors.TextMuted,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = if (favorite) "Remove favorite" else "Add favorite"
                    }
                    .clickable(onClick = onFavorite)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun PlaybackPreviewCard(
    channelName: String,
    readiness: PlaybackReadiness,
    playbackEngine: Media3PlaybackEngine,
    catchUpSupported: Boolean,
    onFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onCatchUp: () -> Unit = {},
    videoSurface: @Composable (Modifier) -> Unit = { surfaceModifier ->
        PlaybackVideoSurface(playbackEngine = playbackEngine, modifier = surfaceModifier)
    },
) {
    var controlsVisible by remember(channelName) { mutableStateOf(false) }
    var interactionRevision by remember(channelName) { mutableStateOf(0) }
    LaunchedEffect(controlsVisible, interactionRevision, channelName) {
        if (controlsVisible) {
            delay(PREVIEW_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            videoSurface(Modifier.fillMaxSize())
            if (readiness != PlaybackReadiness.UNAVAILABLE) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .semantics {
                            contentDescription = "Preview $channelName. Tap to show or hide controls."
                        }
                        .clickable { controlsVisible = !controlsVisible },
                )
            }

            if (readiness == PlaybackReadiness.UNAVAILABLE) {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    OwnPlayColors.Background.copy(alpha = 0.35f),
                                    OwnPlayColors.Background.copy(alpha = 0.88f),
                                ),
                            ),
                        )
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlaybackReadinessMessage(readiness)
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Retry")
                    }
                }
            } else if (readiness == PlaybackReadiness.PREPARING) {
                Text(
                    text = "Preparing playback…",
                    color = OwnPlayColors.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(OwnPlayColors.Background.copy(alpha = 0.68f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (controlsVisible && readiness != PlaybackReadiness.UNAVAILABLE) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    OwnPlayColors.Background.copy(alpha = 0.12f),
                                    OwnPlayColors.Background.copy(alpha = 0.72f),
                                ),
                            ),
                        )
                        .clickable { controlsVisible = false },
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channelName,
                        color = OwnPlayColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = channelName },
                    )
                    if (catchUpSupported) {
                        TextButton(
                            onClick = {
                                interactionRevision += 1
                                onCatchUp()
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Open catch-up for $channelName" },
                        ) {
                            Text("Catch-up")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            interactionRevision += 1
                            onFullscreen()
                        },
                        shape = OwnPlayShapes.Medium,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Open $channelName fullscreen" },
                    ) {
                        Text("Fullscreen")
                    }
                }
            }
        }
    }
}

private const val PREVIEW_CONTROLS_AUTO_HIDE_MS = 3_000L

@Composable
private fun rememberLiveSchedule(
    repository: LiveGuideRepository,
    sourceId: app.ownplay.mobile.sources.domain.SourceId,
    channelId: String?,
): List<LiveProgram> {
    var programs by remember(repository, sourceId, channelId) {
        mutableStateOf<List<LiveProgram>>(emptyList())
    }
    LaunchedEffect(repository, sourceId, channelId) {
        val stableChannelId = channelId?.takeIf(String::isNotBlank)
        programs = if (stableChannelId == null) {
            emptyList()
        } else {
            try {
                LiveGuidePolicy.normalizeSchedule(repository.loadSchedule(sourceId, stableChannelId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    return programs
}

@Composable
internal fun LiveFullEpg(
    sourceId: app.ownplay.mobile.sources.domain.SourceId,
    programs: List<LiveProgram>,
    channelId: String,
    nowEpochSeconds: Long,
    recordings: List<LiveRecording>,
    onSelectProgram: (LiveProgram) -> Unit,
    onRecordChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberContextLazyListState(sourceId.value, "epg", channelId)
    var userHasScrolled by remember(sourceId, channelId) { mutableStateOf(false) }
    var programmaticAnchor by remember(sourceId, channelId) { mutableStateOf(false) }
    LaunchedEffect(listState, sourceId, channelId) {
        snapshotFlow { listState.isScrollInProgress && !programmaticAnchor }
            .collect { manualScroll -> if (manualScroll) userHasScrolled = true }
    }
    LaunchedEffect(channelId, programs) {
        if (userHasScrolled || programs.isEmpty()) return@LaunchedEffect
        val currentIndex = programs.indexOfFirst { program ->
            val start = program.startEpochSeconds
            val end = program.endEpochSeconds
            start != null && end != null && start <= nowEpochSeconds && nowEpochSeconds < end
        }
        if (currentIndex >= 0) {
            programmaticAnchor = true
            try { listState.scrollToItem(currentIndex) } finally { programmaticAnchor = false }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Full EPG",
            color = OwnPlayColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        if (programs.isEmpty()) {
            Text("No EPG schedule is available for this channel.", color = OwnPlayColors.TextMuted)
            TextButton(onClick = onRecordChannel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Record channel")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(
                    items = programs,
                    key = { program ->
                        program.title + ":" + program.startEpochSeconds + ":" + program.endEpochSeconds
                    },
                ) { program ->
                    val isCurrent = program.startEpochSeconds?.let { it <= nowEpochSeconds } == true &&
                        program.endEpochSeconds?.let { nowEpochSeconds < it } == true
                    val matchingRecording = recordings
                        .asSequence()
                        .filter { it.sourceId == sourceId.value && it.channelId == channelId }
                        .filter { recording ->
                            val start = program.startEpochSeconds ?: return@filter false
                            val end = program.endEpochSeconds ?: return@filter false
                            recording.startEpochSeconds < end && recording.endEpochSeconds > start
                        }
                        .maxWithOrNull(compareBy<LiveRecording> { epgRecordingStatusPriority(it.status) }
                            .thenBy { it.scheduledAtEpochMs ?: 0L })
                    val recordingState = matchingRecording?.let { recording ->
                        val status = when (recording.status) {
                            LiveRecordingStatus.SCHEDULED -> when (recording.scheduleArmedState) {
                                app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates.ARMED -> "Scheduled · alarm armed"
                                app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates.NOT_ARMED_PERMISSION -> "Scheduled · exact-alarm permission needed"
                                app.ownplay.mobile.feature.live.domain.LiveRecordingScheduleArmedStates.NOT_ARMED_ERROR -> "Scheduled · alarm not armed"
                                else -> "Scheduled · alarm state unknown"
                            }
                            LiveRecordingStatus.STARTING -> "Starting"
                            LiveRecordingStatus.RECORDING -> "Recording"
                            LiveRecordingStatus.FINALIZING -> "Saving"
                            LiveRecordingStatus.COMPLETED -> "Completed"
                            LiveRecordingStatus.PARTIAL -> "Partial"
                            LiveRecordingStatus.FAILED -> "Failed · ${safeEpgFailureLabel(recording.failureReasonCode)}"
                            LiveRecordingStatus.CANCELLED -> "Cancelled"
                            LiveRecordingStatus.DELETE_PENDING -> "Removal pending"
                        }
                        status
                    }
                    Surface(
                        color = if (isCurrent) OwnPlayColors.Accent.copy(alpha = 0.14f) else OwnPlayColors.Surface,
                        shape = OwnPlayShapes.Small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = buildString {
                                    append(program.title)
                                    append(". ")
                                    append(programTimeRange(program))
                                    append(". Tap for program details.")
                                }
                                stateDescription = listOfNotNull(
                                    if (isCurrent) "Current program" else null,
                                    recordingState,
                                ).joinToString(". ")
                            }
                            .clickable { onSelectProgram(program) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                            ) {
                                Text(
                                    program.title,
                                    color = OwnPlayColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    programTimeRange(program),
                                    color = OwnPlayColors.TextMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                recordingState?.let { state ->
                                    Text(
                                        state,
                                        color = if (matchingRecording?.status == LiveRecordingStatus.FAILED) {
                                            OwnPlayColors.Error
                                        } else {
                                            OwnPlayColors.Accent
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveEpgProgramDetailsDialog(
    program: LiveProgram,
    nowEpochSeconds: Long,
    onAction: (LiveProgram) -> Unit,
    onDismiss: () -> Unit,
) {
    val action = LiveRecordingPolicy.actionFor(program, nowEpochSeconds)
    val start = program.startEpochSeconds
    val end = program.endEpochSeconds
    val validPast = start != null && end != null && start > 0L && end > start && end <= nowEpochSeconds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(program.title.ifBlank { "EPG program details" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(programTimeRange(program), color = OwnPlayColors.TextSecondary)
                Text(
                    when {
                        action == LiveRecordingAction.RECORD_NOW -> "This program is on now."
                        action == LiveRecordingAction.SCHEDULE -> "This program is scheduled for later."
                        validPast -> "This program has ended. Check catch-up availability."
                        else -> "The EPG time window is incomplete or invalid. Recording is unavailable."
                    },
                    color = OwnPlayColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            when {
                action != null -> TextButton(
                    onClick = { onAction(program) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (action == LiveRecordingAction.RECORD_NOW) "Record now" else "Schedule") }
                validPast -> TextButton(
                    onClick = { onAction(program) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Check catch-up") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Close")
            }
        },
    )
}

private fun epgRecordingStatusPriority(status: LiveRecordingStatus): Int = when (status) {
    LiveRecordingStatus.RECORDING -> 8
    LiveRecordingStatus.STARTING -> 7
    LiveRecordingStatus.FINALIZING -> 6
    LiveRecordingStatus.SCHEDULED -> 5
    LiveRecordingStatus.FAILED -> 4
    LiveRecordingStatus.PARTIAL -> 3
    LiveRecordingStatus.COMPLETED -> 2
    LiveRecordingStatus.DELETE_PENDING -> 1
    LiveRecordingStatus.CANCELLED -> 0
}

private fun safeEpgFailureLabel(code: String?): String = when (code) {
    "LIVE_SLOT_USED_BY_PLAYBACK" -> "playback used capacity"
    "LIVE_SLOT_USED_BY_RECORDING" -> "recording capacity unavailable"
    "APP_RECORDING_SERVICE_BUSY" -> "OwnPlay service busy"
    "START_WINDOW_MISSED" -> "start window missed"
    "ANDROID_BACKGROUND_START_DENIED" -> "Android blocked background start"
    null -> "see Downloads for details"
    else -> "see Downloads for details"
}

@Composable
private fun LiveRecordingOptionsDialog(
    request: LiveRecordingDialogRequest,
    nowEpochSeconds: Long,
    catchUpAvailable: Boolean,
    catchUpFormatSupported: Boolean,
    catchUpChecking: Boolean,
    onDismiss: () -> Unit,
    onRecord: (startEpochSeconds: Long, endEpochSeconds: Long, LiveRecordingStopPolicy) -> Unit,
    onDownloadCatchUp: () -> Unit,
) {
    var durationMinutesText by remember(request) { mutableStateOf("60") }
    val durationMinutes = durationMinutesText.toIntOrNull()?.takeIf { it in 1..300 }
    val program = request.program
    val start = program?.startEpochSeconds
    val end = program?.endEpochSeconds
    val validWindow = start != null && end != null && end > start
    val current = validWindow && start!! <= nowEpochSeconds && nowEpochSeconds < end!!
    val future = validWindow && start!! > nowEpochSeconds
    val past = validWindow && end!! <= nowEpochSeconds
    val zone = java.time.ZoneId.systemDefault()
    val initialEnd = remember(request) {
        java.time.Instant.ofEpochSecond(end ?: (nowEpochSeconds + 3_600L)).atZone(zone)
    }
    var customEnd by remember(request) { mutableStateOf(false) }
    var endDate by remember(request) { mutableStateOf(initialEnd.toLocalDate().toString()) }
    var endTime by remember(request) {
        mutableStateOf(initialEnd.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
    }
    val captureStart = if (future) start!! else nowEpochSeconds
    val clockDeadline = app.ownplay.mobile.feature.live.domain.RecordingClockDeadlinePolicy.resolve(
        endDate, endTime, zone, captureStart,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording · ${request.channelName}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    program?.title?.takeIf(String::isNotBlank) ?: "No current EPG program",
                    color = OwnPlayColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    future -> Text("This program will be scheduled for its listed time.", color = OwnPlayColors.TextSecondary)
                    current -> Text("Capture starts now; no earlier part of the program is included.", color = OwnPlayColors.TextSecondary)
                    past -> Text(
                        when {
                            catchUpChecking -> "Checking the source archive…"
                            catchUpAvailable && catchUpFormatSupported -> "This program is archived in a supported finite TS or MP4 format."
                            catchUpAvailable -> "This program is archived, but its format cannot be downloaded safely."
                            else -> "This program is not available in the source archive."
                        },
                        color = OwnPlayColors.TextSecondary,
                    )
                    else -> Text("Without EPG, choose a duration or stop the recording from its notification.", color = OwnPlayColors.TextSecondary)
                }
                if (current || !validWindow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(30, 60, 90, 120, 240).forEach { minutes ->
                            TextButton(onClick = { durationMinutesText = minutes.toString() }) {
                                Text("${minutes}m")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = durationMinutesText,
                        onValueChange = { value -> durationMinutesText = value.filter(Char::isDigit).take(3) },
                        label = { Text("Duration in minutes (1–300)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Text(
                        "Android applies per-app background limits; a long capture can be stopped by the system.",
                        color = OwnPlayColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!past) {
                    TextButton(onClick = { customEnd = !customEnd }) {
                        Text(if (customEnd) "Use duration / EPG end" else "Choose end date and time")
                    }
                    if (customEnd) {
                        OutlinedTextField(
                            value = endDate,
                            onValueChange = { endDate = it.take(10) },
                            label = { Text("End date (YYYY-MM-DD)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = endTime,
                            onValueChange = { endTime = it.take(5) },
                            label = { Text("End time (HH:mm)") },
                            singleLine = true,
                        )
                        Text("Timezone: ${zone.id}", color = OwnPlayColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                        clockDeadline.error?.let { error ->
                            Text(
                                when (error) {
                                    app.ownplay.mobile.feature.live.domain.RecordingClockError.INVALID_DATE_OR_TIME -> "Enter a valid date and 24-hour time."
                                    app.ownplay.mobile.feature.live.domain.RecordingClockError.DST_GAP -> "This time does not exist because the clocks move forward."
                                    app.ownplay.mobile.feature.live.domain.RecordingClockError.DST_OVERLAP -> "This time occurs twice when the clocks move back. Choose another time or use duration."
                                    app.ownplay.mobile.feature.live.domain.RecordingClockError.NOT_AFTER_START -> "The end must be after the recording starts."
                                    app.ownplay.mobile.feature.live.domain.RecordingClockError.TOO_LONG -> "Choose an end within 300 minutes of the recording start."
                                },
                                color = OwnPlayColors.Error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                customEnd && !past -> TextButton(
                    enabled = clockDeadline.epochSeconds != null,
                    onClick = {
                        clockDeadline.epochSeconds?.let { deadline ->
                            onRecord(captureStart, deadline, LiveRecordingStopPolicy.ABSOLUTE_TIME)
                        }
                    },
                ) { Text(if (future) "Schedule until chosen time" else "Record until chosen time") }
                future -> TextButton(
                    onClick = { onRecord(start!!, end!!, LiveRecordingStopPolicy.PROGRAM_END) },
                ) { Text("Schedule") }
                current -> TextButton(
                    onClick = { onRecord(nowEpochSeconds, end!!, LiveRecordingStopPolicy.PROGRAM_END) },
                ) { Text("Until program end") }
                past -> TextButton(enabled = catchUpAvailable && catchUpFormatSupported && !catchUpChecking, onClick = onDownloadCatchUp) {
                    Text(
                        when {
                            catchUpChecking -> "Checking…"
                            catchUpFormatSupported -> "Download Catch-up"
                            catchUpAvailable -> "Unsupported format"
                            else -> "Archive unavailable"
                        },
                    )
                }
                durationMinutes != null -> TextButton(
                    onClick = {
                        onRecord(
                            nowEpochSeconds,
                            nowEpochSeconds + durationMinutes * 60L,
                            LiveRecordingStopPolicy.DEADLINE,
                        )
                    },
                ) { Text("Record for ${durationMinutes}m") }
                else -> TextButton(enabled = false, onClick = {}) { Text("Choose duration") }
            }
        },
        dismissButton = {
            Row {
                if (current && !customEnd && durationMinutes != null) {
                    TextButton(
                        onClick = {
                            onRecord(
                                nowEpochSeconds,
                                nowEpochSeconds + durationMinutes * 60L,
                                LiveRecordingStopPolicy.DEADLINE,
                            )
                        },
                    ) { Text("For ${durationMinutes}m") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun rememberLiveGuide(
    repository: LiveGuideRepository,
    sourceId: app.ownplay.mobile.sources.domain.SourceId,
    channelId: String?,
): LiveNowNext {
    var guide by remember(repository, sourceId, channelId) { mutableStateOf(LiveNowNext()) }
    LaunchedEffect(repository, sourceId, channelId) {
        val stableChannelId = channelId?.takeIf(String::isNotBlank)
        if (stableChannelId == null) {
            guide = LiveNowNext()
            return@LaunchedEffect
        }
        while (true) {
            guide = repository.loadNowNext(sourceId, stableChannelId)
            val nowMs = System.currentTimeMillis()
            val boundary = LiveGuidePolicy.nextBoundaryEpochSeconds(guide, nowMs / 1_000L)
            val waitMs = boundary
                ?.let { ((it * 1_000L) + 100L - nowMs).coerceAtLeast(250L) }
                ?.coerceAtMost(125_000L)
                ?: 125_000L
            delay(waitMs)
        }
    }
    return guide
}

@Composable
private fun rememberLiveCatchUpCatalog(
    repository: LiveCatchUpRepository,
    sourceId: app.ownplay.mobile.sources.domain.SourceId,
    channelId: String?,
    refreshRevision: Int,
): LiveCatchUpCatalog {
    var catalog by remember(repository, sourceId, channelId, refreshRevision) {
        mutableStateOf(LiveCatchUpCatalog(supported = false))
    }
    LaunchedEffect(repository, sourceId, channelId, refreshRevision) {
        val stableChannelId = channelId?.takeIf(String::isNotBlank)
        catalog = if (stableChannelId == null) {
            LiveCatchUpCatalog(supported = false)
        } else {
            repository.loadCatalog(sourceId, stableChannelId)
        }
    }
    return catalog
}

@Composable
private fun rememberEpgClock(): Long {
    var nowEpochSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowEpochSeconds = System.currentTimeMillis() / 1_000L
        }
    }
    return nowEpochSeconds
}

private fun currentProgramLine(program: LiveProgram, nowEpochSeconds: Long): String {
    val progress = LiveGuidePolicy.progressFraction(program, nowEpochSeconds)
        ?.let { value -> "${(value * 100).toInt()}%" }
    return listOf(programTimeRange(program), progress?.let { "${it} complete" })
        .filterNotNull()
        .filter(String::isNotBlank)
        .joinToString(" • ")
}

private fun programTimeRange(program: LiveProgram): String {
    val formatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    val start = program.startEpochSeconds?.let { formatter.format(Date(it * 1_000L)) }
    val end = program.endEpochSeconds?.let { formatter.format(Date(it * 1_000L)) }
    return when {
        start != null && end != null -> "$start–$end"
        start != null -> start
        end != null -> end
        else -> ""
    }
}

@Composable
private fun PlaybackReadinessMessage(readiness: PlaybackReadiness) {
    val message = when (readiness) {
        PlaybackReadiness.IDLE -> null
        PlaybackReadiness.PREPARING -> "Preparing playback…"
        PlaybackReadiness.PREPARED -> null
        PlaybackReadiness.UNAVAILABLE -> "Playback is unavailable for this channel."
    }
    if (message != null) {
        Text(text = message, color = OwnPlayColors.TextMuted)
    }
}

private tailrec fun Context.findLiveActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLiveActivity()
    else -> null
}
