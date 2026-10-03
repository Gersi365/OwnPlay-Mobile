package app.ownplay.mobile.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.ownplay.mobile.OwnPlayApplication
import app.ownplay.mobile.design.OwnPlayColors
import app.ownplay.mobile.downloads.domain.DownloadDetailsNavigation
import app.ownplay.mobile.downloads.ui.DownloadsScreen
import app.ownplay.mobile.feature.library.ui.LibraryScreen
import app.ownplay.mobile.feature.live.ui.LiveScreen
import app.ownplay.mobile.feature.playback.domain.PlaybackPresentation
import app.ownplay.mobile.feature.playback.ui.PlaybackVideoSurface
import app.ownplay.mobile.feature.settings.ui.SettingsScreen
import app.ownplay.mobile.sources.domain.SourceId
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Composable
fun OwnPlayApp(
    downloadDetailsNavigation: DownloadDetailsNavigation? = null,
    onDownloadDetailsNavigationConsumed: () -> Unit = {},
    sourceSettingsNavigation: SourceId? = null,
    onSourceSettingsNavigationConsumed: () -> Unit = {},
    openRecordingsRequestId: Long = 0L,
    onOpenRecordingsRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val playbackState by services.playbackSessionController.state.collectAsState()
    val isStandaloneOfflinePlayback =
        (playbackState.target as? app.ownplay.mobile.feature.playback.domain.PlaybackTarget.CatchUp)
            ?.let { it.localMediaUri != null || it.offlineDownloadId != null } == true
    val isFullscreenPlayback =
        playbackState.target != null && playbackState.presentation == PlaybackPresentation.FULLSCREEN
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
    val activeSource by activeSourceFlow.collectAsState(initial = null)
    val sourceStateKey = activeSource?.sourceId?.value ?: "no-source"
    val bootstrapStateFlow = remember(services.sourceRepository) {
        combine(
            services.sourceRepository.observeSources(),
            services.sourceRepository.observeActiveSource(),
        ) { sources, activeSource ->
            AppBootstrapResolver.resolve(
                AppBootstrapSnapshot(
                    initialized = true,
                    localSourceState = LocalSourceGateResolver.resolve(sources, activeSource),
                ),
            )
        }
    }
    val bootstrapState by bootstrapStateFlow.collectAsState(
        initial = AppBootstrapState.BOOTSTRAPPING,
    )

    LaunchedEffect(activeSourceFlow, services.playbackSessionController) {
        activeSourceFlow.collect { activeSource ->
            services.playbackSessionController.reconcileActiveSource(activeSource?.sourceId)
        }
    }

    var selectedName by rememberSaveable {
        mutableStateOf(AppDestination.LIVE.name)
    }
    val selected = AppDestination.entries.firstOrNull { it.name == selectedName }
        ?: AppDestination.LIVE
    val destinationStateHolder = key(sourceStateKey) { rememberSaveableStateHolder() }
    var settingsEntryId by rememberSaveable { mutableStateOf(0L) }
    val settingsExitGuard = remember { SettingsExitGuard() }
    val navigationScope = rememberCoroutineScope()
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var internalDownloadDetailsNavigation by remember {
        mutableStateOf<DownloadDetailsNavigation?>(null)
    }
    var returnToDownloadsAfterDetail by rememberSaveable { mutableStateOf(false) }
    var openSourcesRequested by rememberSaveable { mutableStateOf(false) }
    var provisioningSourcesOpen by rememberSaveable { mutableStateOf(false) }
    val activeDownloadDetailsNavigation =
        internalDownloadDetailsNavigation ?: downloadDetailsNavigation

    fun commitNavigation(destination: AppDestination) {
        if (destination == selected) return
            if (selected == AppDestination.SETTINGS) {
                destinationStateHolder.removeState("destination:SETTINGS:$settingsEntryId")
            }
            if (destination == AppDestination.SETTINGS &&
                ScreenPositionPolicy.resolve(ScreenFamily.SETTINGS, ScreenNavigationEvent.ENTER_PAGE) == ScreenPositionAction.RESET_TOP
            ) {
                settingsEntryId += 1L
            }
            selectedName = destination.name
    }

    fun requestNavigation(action: () -> Unit) {
        if (selected == AppDestination.SETTINGS) settingsExitGuard.requestExit(action) else action()
    }

    fun navigateTo(destination: AppDestination, onAccepted: () -> Unit = {}) {
        if (destination == selected) return
        requestNavigation {
            onAccepted()
            commitNavigation(destination)
        }
    }

    LaunchedEffect(bootstrapState, isStandaloneOfflinePlayback) {
        when (bootstrapState) {
            AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR -> provisioningSourcesOpen = true
            AppBootstrapState.NEEDS_PROVISIONING,
            AppBootstrapState.READY -> provisioningSourcesOpen = false
            AppBootstrapState.BOOTSTRAPPING -> Unit
        }
        if (
            bootstrapState != AppBootstrapState.READY &&
            bootstrapState != AppBootstrapState.BOOTSTRAPPING &&
            !isStandaloneOfflinePlayback
        ) {
            services.playbackSessionController.clear()
        }
    }

    if (bootstrapState != AppBootstrapState.READY && !isStandaloneOfflinePlayback) {
        if (bootstrapState == AppBootstrapState.BOOTSTRAPPING) {
            ProvisioningLoadingScreen(modifier = Modifier.fillMaxSize())
        } else if (provisioningSourcesOpen) {
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                sourceProvisioningMode = true,
                onSourceProvisioningBack = { provisioningSourcesOpen = false },
            )
        } else {
            ProvisioningScreen(
                bootstrapState = bootstrapState,
                onManageSources = { provisioningSourcesOpen = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    if (
        playbackState.target != null &&
        playbackState.presentation == PlaybackPresentation.PICTURE_IN_PICTURE
    ) {
        PlaybackVideoSurface(
            playbackEngine = services.playbackEngine,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LaunchedEffect(activeDownloadDetailsNavigation) {
        val request = activeDownloadDetailsNavigation ?: return@LaunchedEffect
        requestNavigation {
            navigationScope.launch {
                services.playbackSessionController.clear()
                services.sourceRepository.setActiveSource(request.sourceId)
                if (request.mediaKind == app.ownplay.mobile.downloads.domain.DownloadMediaKind.CATCH_UP) {
                    internalDownloadDetailsNavigation = null
                    onDownloadDetailsNavigationConsumed()
                    returnToDownloadsAfterDetail = false
                    commitNavigation(AppDestination.DOWNLOADS)
                } else {
                    commitNavigation(AppDestination.LIBRARY)
                }
            }
        }
    }

    LaunchedEffect(openRecordingsRequestId) {
        if (openRecordingsRequestId <= 0L) return@LaunchedEffect
        navigateTo(AppDestination.DOWNLOADS) {
            services.playbackSessionController.clear()
            returnToDownloadsAfterDetail = false
        }
    }

    LaunchedEffect(sourceSettingsNavigation) {
        sourceSettingsNavigation ?: return@LaunchedEffect
        services.playbackSessionController.clear()
        navigateTo(AppDestination.SETTINGS)
    }

    BackHandler {
        when (selected.rootBackAction) {
            AppRootBackAction.RETURN_TO_PRIMARY -> {
                showExitConfirmation = false
                navigateTo(selected.bottomNavigationSelection)
            }
            AppRootBackAction.CONFIRM_EXIT -> {
                showExitConfirmation = true
            }
        }
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Exit OwnPlay?") },
            text = { Text("Do you want to close OwnPlay?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        services.playbackSessionController.clear()
                        context.findActivity()?.finish()
                    },
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        containerColor = OwnPlayColors.Background,
        bottomBar = {
            if (!isFullscreenPlayback) {
                OwnPlayBottomBar(
                    selected = selected.bottomNavigationSelection,
                    onSelected = { destination ->
                        if (destination != selected) {
                            navigateTo(destination) {
                                returnToDownloadsAfterDetail = false
                                internalDownloadDetailsNavigation = null
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        val modifier = if (isFullscreenPlayback) {
            Modifier.fillMaxSize()
        } else {
            Modifier.padding(innerPadding)
        }
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val enterOffset = if (forward) { width: Int -> width / 10 } else { width: Int -> -width / 10 }
                val exitOffset = if (forward) { width: Int -> -width / 14 } else { width: Int -> width / 14 }
                (fadeIn(animationSpec = tween(180)) +
                    slideInHorizontally(animationSpec = tween(200), initialOffsetX = enterOffset))
                    .togetherWith(
                        fadeOut(animationSpec = tween(140)) +
                            slideOutHorizontally(animationSpec = tween(160), targetOffsetX = exitOffset),
                    )
            },
            label = "primary-navigation",
        ) { destination ->
            val entryKey = if (destination == AppDestination.SETTINGS) settingsEntryId.toString() else sourceStateKey
            destinationStateHolder.SaveableStateProvider(
                key = "destination:${destination.name}:$entryKey",
            ) {
                when (destination) {
                AppDestination.LIVE -> LiveScreen(
                    modifier = modifier,
                    onReturnToDownloads = { navigateTo(AppDestination.DOWNLOADS) },
                    onOpenSettings = {
                        openSourcesRequested = true
                        navigateTo(AppDestination.SETTINGS)
                    },
                )
                AppDestination.LIBRARY -> LibraryScreen(
                    modifier = modifier,
                    onOpenSettings = {
                        openSourcesRequested = true
                        navigateTo(AppDestination.SETTINGS)
                    },
                    openDownloadDetails = activeDownloadDetailsNavigation,
                    onDownloadDetailsNavigationConsumed = {
                        if (internalDownloadDetailsNavigation != null) {
                            internalDownloadDetailsNavigation = null
                        } else {
                            onDownloadDetailsNavigationConsumed()
                        }
                    },
                    onReturnFromDownloadDetails = if (returnToDownloadsAfterDetail) {
                        {
                            returnToDownloadsAfterDetail = false
                            navigateTo(AppDestination.DOWNLOADS)
                        }
                    } else {
                        null
                    },
                    onOpenDownloads = {
                        returnToDownloadsAfterDetail = false
                        internalDownloadDetailsNavigation = null
                        navigateTo(AppDestination.DOWNLOADS)
                    },
                )
                AppDestination.DOWNLOADS -> DownloadsScreen(
                    openRecordingsRequestId = openRecordingsRequestId,
                    onOpenRecordingsRequestConsumed = onOpenRecordingsRequestConsumed,
                    modifier = modifier,
                    onPlaybackStarted = {
                        returnToDownloadsAfterDetail = false
                        navigateTo(AppDestination.LIBRARY)
                    },
                    onSavedTvPlaybackStarted = {
                        returnToDownloadsAfterDetail = false
                        navigateTo(AppDestination.LIVE)
                    },
                    onOpenDetails = { request ->
                        if (request.mediaKind == app.ownplay.mobile.downloads.domain.DownloadMediaKind.CATCH_UP) {
                            returnToDownloadsAfterDetail = false
                            navigateTo(AppDestination.DOWNLOADS)
                        } else {
                            internalDownloadDetailsNavigation = request
                            returnToDownloadsAfterDetail = true
                            navigateTo(AppDestination.LIBRARY)
                        }
                    },
                    onOpenSettings = {
                        openSourcesRequested = true
                        navigateTo(AppDestination.SETTINGS)
                    },
                    onOpenLibrary = {
                        returnToDownloadsAfterDetail = false
                        internalDownloadDetailsNavigation = null
                        navigateTo(AppDestination.LIBRARY)
                    },
                )
                AppDestination.SETTINGS -> SettingsScreen(
                    exitGuard = settingsExitGuard,
                    modifier = modifier,
                    openSources = sourceSettingsNavigation != null || openSourcesRequested,
                    onOpenSourcesConsumed = {
                        if (openSourcesRequested) {
                            openSourcesRequested = false
                        }
                        if (sourceSettingsNavigation != null) {
                            onSourceSettingsNavigationConsumed()
                        }
                    },
                )
                }
            }
        }
    }
}


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
