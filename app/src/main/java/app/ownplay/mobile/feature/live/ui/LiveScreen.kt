package app.ownplay.mobile.feature.live.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun LiveScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val activeSource by activeSourceFlow.collectAsState(initial = null)
    val displayPreferences by services.displayPreferencesRepository.preferences.collectAsState(
        initial = DisplayPreferences(),
    )
    val sourceStateHolder = rememberSaveableStateHolder()

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
                if (result is app.ownplay.mobile.sources.domain.SourceRefreshResult.Failure) {
                    initialRefreshError = result.safeMessage ?: "Source refresh failed."
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

@Composable
private fun LiveSourceScreen(
    source: SourceSummary,
    repository: LiveOrganizationRepository,
    guideRepository: LiveGuideRepository,
    catchUpRepository: LiveCatchUpRepository,
    liveRecordingRepository: app.ownplay.mobile.feature.live.domain.LiveRecordingRepository,
    liveRecordingScheduler: app.ownplay.mobile.feature.live.data.AndroidLiveRecordingScheduler,
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
    val categoryListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val catchUpListState = rememberLazyListState()
    var catchUpRefreshRevision by rememberSaveable(sourceId.value) { mutableStateOf(0) }
    var catchUpChannelIds by remember(sourceId.value) { mutableStateOf<Set<String>>(emptySet()) }
    var catchUpChannelsLoading by remember(sourceId.value) { mutableStateOf(false) }
    var selectedCatchUpChannelId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }
    var pendingResumeProgram by remember { mutableStateOf<LiveCatchUpProgram?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var fullscreenAnchorIndex by rememberSaveable(sourceId.value) { mutableStateOf<Int?>(null) }
    var rememberedPlayerChannelId by rememberSaveable(sourceId.value) { mutableStateOf<String?>(null) }

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
    val selectedGuide = rememberLiveGuide(guideRepository, sourceId, playbackTarget?.channelId)
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
        pageName = LiveBrowsePage.SEARCH.name
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

    fun recordProgram(program: LiveProgram) {
        val channelId = playbackTarget?.channelId ?: return
        val action = LiveRecordingPolicy.actionFor(program, epgNowEpochSeconds) ?: return
        val start = program.startEpochSeconds ?: return
        val end = program.endEpochSeconds ?: return
        val recording = LiveRecording(
            recordingId = LiveRecordingPolicy.recordingId(sourceId.value, channelId, start),
            sourceId = sourceId.value,
            channelId = channelId,
            channelName = playbackChannelName ?: "Live channel",
            title = program.title,
            startEpochSeconds = start,
            endEpochSeconds = end,
            status = app.ownplay.mobile.feature.live.domain.LiveRecordingStatus.SCHEDULED,
        )
        if (action == LiveRecordingAction.RECORD_NOW) {
            operationMessage = if (liveRecordingScheduler.startNow(recording)) {
                "Recording started. See Downloads > Recordings."
            } else {
                "Android could not start this recording."
            }
            return
        }
        when (liveRecordingScheduler.schedule(recording)) {
            LiveRecordingScheduleResult.SCHEDULED ->
                operationMessage = "Recording scheduled."
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
            LiveRecordingScheduleResult.OVERLAPPING_RECORDING ->
                operationMessage = "Another Live recording is already scheduled for that time."
            LiveRecordingScheduleResult.FAILED ->
                operationMessage = "The recording could not be scheduled."
        }
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
            -> pageName = LiveBrowsePage.HOME.name
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
                                pageName = LiveBrowsePage.CATCH_UP.name
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Catch-up")
                        }
                        TextButton(
                            onClick = { pageName = LiveBrowsePage.FAVORITES.name },
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
                                        pageName = LiveBrowsePage.CATEGORY.name
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
                    onBack = { pageName = LiveBrowsePage.HOME.name },
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
                    onValueChange = { searchQuery = it },
                    label = { Text("Search channels") },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            TextButton(onClick = { searchQuery = "" }) {
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
                        TextButton(onClick = { pageName = LiveBrowsePage.HOME.name }) { Text("Back") }
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
                    )
                    LiveFullEpg(
                        programs = fullGuide,
                        nowEpochSeconds = epgNowEpochSeconds,
                        onRecordProgram = ::recordProgram,
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
            guide = selectedGuide,
            nowEpochSeconds = epgNowEpochSeconds,
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
            onRecordNow = { selectedGuide.now?.let(::recordProgram) },
            onScheduleNext = { selectedGuide.next?.let(::recordProgram) },
            onCatchUp = {
                catchUpRefreshRevision += 1
                selectedCatchUpChannelId = playbackTarget.channelId
                catchUpReturnPageName = LiveBrowsePage.PLAYER.name
                operationMessage = null
                pageName = LiveBrowsePage.CATCH_UP.name
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
                        text = currentProgramLine(current, nowEpochSeconds),
                        color = OwnPlayColors.TextSecondary,
                        maxLines = 1,
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
private fun PlaybackPreviewCard(
    channelName: String,
    readiness: PlaybackReadiness,
    playbackEngine: Media3PlaybackEngine,
    catchUpSupported: Boolean,
    onFullscreen: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            PlaybackVideoSurface(
                playbackEngine = playbackEngine,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = "Open $channelName fullscreen"
                    }
                    .clickable(onClick = onFullscreen),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channelName,
                color = OwnPlayColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (catchUpSupported) {
                Text(
                    text = "◷",
                    color = OwnPlayColors.TextSecondary,
                    modifier = Modifier.semantics {
                        contentDescription = "Catch-up available for this channel"
                    },
                )
            }
            FilledTonalButton(
                onClick = onFullscreen,
                shape = OwnPlayShapes.Medium,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Open $channelName fullscreen"
                    },
            ) {
                Text("Fullscreen")
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PlaybackReadinessMessage(readiness)
            if (readiness == PlaybackReadiness.UNAVAILABLE) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun rememberLiveSchedule(
    repository: LiveGuideRepository,
    sourceId: app.ownplay.mobile.sources.domain.SourceId,
    channelId: String?,
): List<LiveProgram> {
    var programs by remember(repository, sourceId, channelId) { mutableStateOf(emptyList()) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveFullEpg(
    programs: List<LiveProgram>,
    nowEpochSeconds: Long,
    onRecordProgram: (LiveProgram) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(
                    items = programs,
                    key = { program ->
                        program.title + ":" + program.startEpochSeconds + ":" + program.endEpochSeconds
                    },
                ) { program ->
                    val action = LiveRecordingPolicy.actionFor(program, nowEpochSeconds)
                    Surface(
                        color = OwnPlayColors.Surface,
                        shape = OwnPlayShapes.Small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { if (action != null) onRecordProgram(program) },
                            ),
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
                            }
                            if (action != null) {
                                TextButton(
                                    onClick = { onRecordProgram(program) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics {
                                            contentDescription = when (action) {
                                                LiveRecordingAction.RECORD_NOW ->
                                                    "Record now: " + program.title
                                                LiveRecordingAction.SCHEDULE ->
                                                    "Schedule recording: " + program.title
                                            }
                                        },
                                ) {
                                    Text(
                                        when (action) {
                                            LiveRecordingAction.RECORD_NOW -> "Record now"
                                            LiveRecordingAction.SCHEDULE -> "Schedule"
                                        },
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
    return listOf(programTimeRange(program), progress, program.title)
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
