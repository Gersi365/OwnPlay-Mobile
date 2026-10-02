package app.ownplay.mobile.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@Composable
fun OwnPlayApp(
    downloadDetailsNavigation: DownloadDetailsNavigation? = null,
    onDownloadDetailsNavigationConsumed: () -> Unit = {},
    sourceSettingsNavigation: SourceId? = null,
    onSourceSettingsNavigationConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as OwnPlayApplication
    val services = remember(application) { application.services }
    val playbackState by services.playbackSessionController.state.collectAsState()
    val isFullscreenPlayback =
        playbackState.target != null && playbackState.presentation == PlaybackPresentation.FULLSCREEN
    val activeSourceFlow = remember(services.sourceRepository) {
        services.sourceRepository.observeActiveSource()
    }
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
    val destinationStateHolder = rememberSaveableStateHolder()
    var liveStateGeneration by rememberSaveable { mutableStateOf(0) }
    var libraryStateGeneration by rememberSaveable { mutableStateOf(0) }
    var settingsStateGeneration by rememberSaveable { mutableStateOf(0) }
    var liveLastLeftAtMs by rememberSaveable { mutableStateOf(0L) }
    var libraryLastLeftAtMs by rememberSaveable { mutableStateOf(0L) }
    var settingsLastLeftAtMs by rememberSaveable { mutableStateOf(0L) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var internalDownloadDetailsNavigation by remember {
        mutableStateOf<DownloadDetailsNavigation?>(null)
    }
    var returnToDownloadsAfterDetail by rememberSaveable { mutableStateOf(false) }
    var openSourcesRequested by rememberSaveable { mutableStateOf(false) }
    var provisioningSourcesOpen by rememberSaveable { mutableStateOf(false) }
    val activeDownloadDetailsNavigation =
        internalDownloadDetailsNavigation ?: downloadDetailsNavigation

    fun destinationGeneration(destination: AppDestination): Int = when (destination) {
        AppDestination.LIVE -> liveStateGeneration
        AppDestination.LIBRARY -> libraryStateGeneration
        AppDestination.SETTINGS -> settingsStateGeneration
        AppDestination.DOWNLOADS -> 0
    }

    fun lastLeftAtMs(destination: AppDestination): Long = when (destination) {
        AppDestination.LIVE -> liveLastLeftAtMs
        AppDestination.LIBRARY -> libraryLastLeftAtMs
        AppDestination.SETTINGS -> settingsLastLeftAtMs
        AppDestination.DOWNLOADS -> 0L
    }

    fun rememberDeparture(destination: AppDestination, nowMs: Long) {
        when (destination) {
            AppDestination.LIVE -> liveLastLeftAtMs = nowMs
            AppDestination.LIBRARY -> libraryLastLeftAtMs = nowMs
            AppDestination.SETTINGS -> settingsLastLeftAtMs = nowMs
            AppDestination.DOWNLOADS -> Unit
        }
    }

    fun clearDeparture(destination: AppDestination) {
        when (destination) {
            AppDestination.LIVE -> liveLastLeftAtMs = 0L
            AppDestination.LIBRARY -> libraryLastLeftAtMs = 0L
            AppDestination.SETTINGS -> settingsLastLeftAtMs = 0L
            AppDestination.DOWNLOADS -> Unit
        }
    }

    fun expireDestinationStateIfNeeded(destination: AppDestination, nowMs: Long) {
        if (!destination.primary) return
        val leftAtMs = lastLeftAtMs(destination)
        if (leftAtMs <= 0L) return
        val expired = nowMs < leftAtMs || nowMs - leftAtMs >= DESTINATION_STATE_RETENTION_MS
        if (expired) {
            val oldGeneration = destinationGeneration(destination)
            destinationStateHolder.removeState(
                "destination:${destination.name}:$oldGeneration",
            )
            when (destination) {
                AppDestination.LIVE -> liveStateGeneration += 1
                AppDestination.LIBRARY -> libraryStateGeneration += 1
                AppDestination.SETTINGS -> settingsStateGeneration += 1
                AppDestination.DOWNLOADS -> Unit
            }
        }
        clearDeparture(destination)
    }

    fun navigateTo(destination: AppDestination) {
        if (destination == selected) return
        val nowMs = SystemClock.elapsedRealtime()
        rememberDeparture(selected, nowMs)
        expireDestinationStateIfNeeded(destination, nowMs)
        selectedName = destination.name
    }

    LaunchedEffect(bootstrapState) {
        when (bootstrapState) {
            AppBootstrapState.NEEDS_SOURCE_SELECTION_OR_REPAIR -> provisioningSourcesOpen = true
            AppBootstrapState.NEEDS_PROVISIONING,
            AppBootstrapState.READY -> provisioningSourcesOpen = false
            AppBootstrapState.BOOTSTRAPPING -> Unit
        }
        if (
            bootstrapState != AppBootstrapState.READY &&
            bootstrapState != AppBootstrapState.BOOTSTRAPPING
        ) {
            services.playbackSessionController.clear()
        }
    }

    if (bootstrapState != AppBootstrapState.READY) {
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
        services.playbackSessionController.clear()
        services.sourceRepository.setActiveSource(request.sourceId)
        navigateTo(AppDestination.LIBRARY)
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
                            returnToDownloadsAfterDetail = false
                            internalDownloadDetailsNavigation = null
                            navigateTo(destination)
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
            val destinationStateGeneration = destinationGeneration(destination)
            destinationStateHolder.SaveableStateProvider(
                key = "destination:${destination.name}:$destinationStateGeneration",
            ) {
                when (destination) {
                AppDestination.LIVE -> LiveScreen(
                    modifier = modifier,
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
                    modifier = modifier,
                    onPlaybackStarted = {
                        returnToDownloadsAfterDetail = false
                        navigateTo(AppDestination.LIBRARY)
                    },
                    onOpenDetails = { request ->
                        internalDownloadDetailsNavigation = request
                        returnToDownloadsAfterDetail = true
                        navigateTo(AppDestination.LIBRARY)
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


private const val DESTINATION_STATE_RETENTION_MS = 5 * 60 * 1000L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
