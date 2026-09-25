package app.ownplay.mobile.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
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
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    var internalDownloadDetailsNavigation by remember {
        mutableStateOf<DownloadDetailsNavigation?>(null)
    }
    var returnToDownloadsAfterDetail by rememberSaveable { mutableStateOf(false) }
    var openSourcesRequested by rememberSaveable { mutableStateOf(false) }
    val activeDownloadDetailsNavigation =
        internalDownloadDetailsNavigation ?: downloadDetailsNavigation

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
        selectedName = AppDestination.LIBRARY.name
    }

    LaunchedEffect(sourceSettingsNavigation) {
        sourceSettingsNavigation ?: return@LaunchedEffect
        services.playbackSessionController.clear()
        selectedName = AppDestination.SETTINGS.name
    }

    BackHandler {
        when (selected.rootBackAction) {
            AppRootBackAction.RETURN_TO_PRIMARY -> {
                showExitConfirmation = false
                selectedName = selected.bottomNavigationSelection.name
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
                            services.playbackSessionController.clear()
                            returnToDownloadsAfterDetail = false
                            internalDownloadDetailsNavigation = null
                            selectedName = destination.name
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
        destinationStateHolder.SaveableStateProvider(
            key = "destination:" + selected.name,
        ) {
            when (selected) {
                AppDestination.LIVE -> LiveScreen(
                    modifier = modifier,
                    onOpenSettings = {
                        openSourcesRequested = true
                        selectedName = AppDestination.SETTINGS.name
                    },
                )
                AppDestination.LIBRARY -> LibraryScreen(
                    modifier = modifier,
                    onOpenSettings = {
                        openSourcesRequested = true
                        selectedName = AppDestination.SETTINGS.name
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
                            selectedName = AppDestination.DOWNLOADS.name
                        }
                    } else {
                        null
                    },
                    onOpenDownloads = {
                        returnToDownloadsAfterDetail = false
                        internalDownloadDetailsNavigation = null
                        selectedName = AppDestination.DOWNLOADS.name
                    },
                )
                AppDestination.DOWNLOADS -> DownloadsScreen(
                    modifier = modifier,
                    onPlaybackStarted = {
                        returnToDownloadsAfterDetail = false
                        selectedName = AppDestination.LIBRARY.name
                    },
                    onOpenDetails = { request ->
                        internalDownloadDetailsNavigation = request
                        returnToDownloadsAfterDetail = true
                        selectedName = AppDestination.LIBRARY.name
                    },
                    onOpenSettings = {
                        openSourcesRequested = true
                        selectedName = AppDestination.SETTINGS.name
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


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
